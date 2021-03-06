/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onosproject.drivers.p4runtime;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Striped;
import org.onlab.util.SharedExecutors;
import org.onosproject.drivers.p4runtime.mirror.P4RuntimeActionProfileMemberMirror;
import org.onosproject.drivers.p4runtime.mirror.P4RuntimeGroupMirror;
import org.onosproject.drivers.p4runtime.mirror.TimedEntry;
import org.onosproject.net.DeviceId;
import org.onosproject.net.group.DefaultGroup;
import org.onosproject.net.group.DefaultGroupDescription;
import org.onosproject.net.group.Group;
import org.onosproject.net.group.GroupDescription;
import org.onosproject.net.group.GroupOperation;
import org.onosproject.net.group.GroupOperations;
import org.onosproject.net.group.GroupProgrammable;
import org.onosproject.net.group.GroupStore;
import org.onosproject.net.pi.model.PiActionId;
import org.onosproject.net.pi.model.PiActionProfileId;
import org.onosproject.net.pi.model.PiActionProfileModel;
import org.onosproject.net.pi.runtime.PiAction;
import org.onosproject.net.pi.runtime.PiActionGroup;
import org.onosproject.net.pi.runtime.PiActionGroupHandle;
import org.onosproject.net.pi.runtime.PiActionGroupMember;
import org.onosproject.net.pi.runtime.PiActionGroupMemberHandle;
import org.onosproject.net.pi.runtime.PiActionGroupMemberId;
import org.onosproject.net.pi.service.PiGroupTranslator;
import org.onosproject.net.pi.service.PiTranslatedEntity;
import org.onosproject.net.pi.service.PiTranslationException;
import org.onosproject.p4runtime.api.P4RuntimeClient;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.onosproject.p4runtime.api.P4RuntimeClient.WriteOperationType.DELETE;
import static org.onosproject.p4runtime.api.P4RuntimeClient.WriteOperationType.INSERT;
import static org.onosproject.p4runtime.api.P4RuntimeClient.WriteOperationType.MODIFY;

/**
 * Implementation of GroupProgrammable to handle action profile groups in
 * P4Runtime.
 */
public class P4RuntimeActionGroupProgrammable
        extends AbstractP4RuntimeHandlerBehaviour
        implements GroupProgrammable {

    // If true, we avoid querying the device and return what's already known by
    // the ONOS store.
    private static final String READ_ACTION_GROUPS_FROM_MIRROR = "actionGroupReadFromMirror";
    private static final boolean DEFAULT_READ_ACTION_GROUPS_FROM_MIRROR = false;

    protected GroupStore groupStore;
    private P4RuntimeGroupMirror groupMirror;
    private P4RuntimeActionProfileMemberMirror memberMirror;
    private PiGroupTranslator groupTranslator;

    // Needed to synchronize operations over the same group.
    private static final Striped<Lock> STRIPED_LOCKS = Striped.lock(30);

    @Override
    protected boolean setupBehaviour() {
        if (!super.setupBehaviour()) {
            return false;
        }
        groupMirror = this.handler().get(P4RuntimeGroupMirror.class);
        memberMirror = this.handler().get(P4RuntimeActionProfileMemberMirror.class);
        groupStore = handler().get(GroupStore.class);
        groupTranslator = piTranslationService.groupTranslator();
        return true;
    }

    @Override
    public void performGroupOperation(DeviceId deviceId,
                                      GroupOperations groupOps) {
        if (!setupBehaviour()) {
            return;
        }

        groupOps.operations().stream()
                .filter(op -> !op.groupType().equals(GroupDescription.Type.ALL))
                .forEach(op -> {
                    // ONOS-7785 We need app cookie (action profile id) from the group
                    Group groupOnStore = groupStore.getGroup(deviceId, op.groupId());
                    GroupDescription groupDesc = new DefaultGroupDescription(
                            deviceId, op.groupType(), op.buckets(), groupOnStore.appCookie(),
                            op.groupId().id(), groupOnStore.appId());
                    DefaultGroup groupToApply = new DefaultGroup(op.groupId(), groupDesc);
                    processGroupOperation(groupToApply, op.opType());
                });
    }

    @Override
    public Collection<Group> getGroups() {
        if (!setupBehaviour()) {
            return Collections.emptyList();
        }
        return getActionGroups();
    }

    private Collection<Group> getActionGroups() {

        if (driverBoolProperty(READ_ACTION_GROUPS_FROM_MIRROR,
                               DEFAULT_READ_ACTION_GROUPS_FROM_MIRROR)) {
            return getActionGroupsFromMirror();
        }

        final Collection<PiActionProfileId> actionProfileIds = pipeconf.pipelineModel()
                .actionProfiles()
                .stream()
                .map(PiActionProfileModel::id)
                .collect(Collectors.toList());
        final List<PiActionGroup> groupsOnDevice = actionProfileIds.stream()
                .flatMap(this::streamGroupsFromDevice)
                .collect(Collectors.toList());
        final Set<PiActionGroupMemberHandle> membersOnDevice = actionProfileIds
                .stream()
                .flatMap(actProfId -> getMembersFromDevice(actProfId)
                        .stream()
                        .map(memberId -> PiActionGroupMemberHandle.of(
                                deviceId, actProfId, memberId)))
                .collect(Collectors.toSet());

        if (groupsOnDevice.isEmpty()) {
            return Collections.emptyList();
        }

        // Sync mirrors.
        syncGroupMirror(groupsOnDevice);
        syncMemberMirror(membersOnDevice);

        final List<Group> result = Lists.newArrayList();
        final List<PiActionGroup> inconsistentGroups = Lists.newArrayList();
        final List<PiActionGroup> validGroups = Lists.newArrayList();

        for (PiActionGroup piGroup : groupsOnDevice) {
            final Group pdGroup = forgeGroupEntry(piGroup);
            if (pdGroup == null) {
                // Entry is on device but unknown to translation service or
                // device mirror. Inconsistent. Mark for removal.
                inconsistentGroups.add(piGroup);
            } else {
                validGroups.add(piGroup);
                result.add(pdGroup);
            }
        }

        // Trigger clean up of inconsistent groups and members. This will also
        // remove all members that are not used by any group, and update the
        // mirror accordingly.
        final Set<PiActionGroupMemberHandle> membersToKeep = validGroups.stream()
                .flatMap(g -> g.members().stream())
                .map(m -> PiActionGroupMemberHandle.of(deviceId, m))
                .collect(Collectors.toSet());
        final Set<PiActionGroupMemberHandle> inconsistentMembers = Sets.difference(
                membersOnDevice, membersToKeep);
        SharedExecutors.getSingleThreadExecutor().execute(
                () -> cleanUpInconsistentGroupsAndMembers(
                        inconsistentGroups, inconsistentMembers));

        return result;
    }

    private void syncGroupMirror(Collection<PiActionGroup> groups) {
        Map<PiActionGroupHandle, PiActionGroup> handleMap = Maps.newHashMap();
        groups.forEach(g -> handleMap.put(PiActionGroupHandle.of(deviceId, g), g));
        groupMirror.sync(deviceId, handleMap);
    }

    private void syncMemberMirror(Collection<PiActionGroupMemberHandle> memberHandles) {
        Map<PiActionGroupMemberHandle, PiActionGroupMember> handleMap = Maps.newHashMap();
       memberHandles.forEach(handle -> handleMap.put(
                handle, dummyMember(handle.actionProfileId(), handle.memberId())));
        memberMirror.sync(deviceId, handleMap);
    }

    private Collection<Group> getActionGroupsFromMirror() {
        return groupMirror.getAll(deviceId).stream()
                .map(TimedEntry::entry)
                .map(this::forgeGroupEntry)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private void cleanUpInconsistentGroupsAndMembers(Collection<PiActionGroup> groupsToRemove,
                                                     Collection<PiActionGroupMemberHandle> membersToRemove) {
        if (!groupsToRemove.isEmpty()) {
            log.warn("Found {} inconsistent action profile groups on {}, removing them...",
                     groupsToRemove.size(), deviceId);
            groupsToRemove.forEach(piGroup -> {
                log.debug(piGroup.toString());
                processGroup(piGroup, null, Operation.REMOVE);
            });
        }
        if (!membersToRemove.isEmpty()) {
            log.warn("Found {} inconsistent action profile members on {}, removing them...",
                     membersToRemove.size(), deviceId);
            // FIXME: implement client call to remove members from multiple
            // action profiles in one shot.
            final ListMultimap<PiActionProfileId, PiActionGroupMemberId>
                    membersByActProfId = ArrayListMultimap.create();
            membersToRemove.forEach(m -> membersByActProfId.put(
                    m.actionProfileId(), m.memberId()));
            membersByActProfId.keySet().forEach(actProfId -> {
                List<PiActionGroupMemberId> removedMembers = getFutureWithDeadline(
                        client.removeActionProfileMembers(
                                actProfId, membersByActProfId.get(actProfId), pipeconf),
                        "cleaning up action profile members", Collections.emptyList());
                // Update member mirror.
                removedMembers.stream()
                        .map(id -> PiActionGroupMemberHandle.of(deviceId, actProfId, id))
                        .forEach(memberMirror::remove);
            });
        }
    }

    private Stream<PiActionGroup> streamGroupsFromDevice(PiActionProfileId actProfId) {
        // TODO: implement P4Runtime client call to read all groups with one call
        // Good if pipeline has multiple action profiles.
        final Collection<PiActionGroup> groups = getFutureWithDeadline(
                client.dumpGroups(actProfId, pipeconf),
                "dumping groups", Collections.emptyList());
        return groups.stream();
    }

    private List<PiActionGroupMemberId> getMembersFromDevice(PiActionProfileId actProfId) {
        // TODO: implement P4Runtime client call to read all members with one call
        // Good if pipeline has multiple action profiles.
        return getFutureWithDeadline(
                client.dumpActionProfileMemberIds(actProfId, pipeconf),
                "dumping action profile ids", Collections.emptyList());
    }

    private Group forgeGroupEntry(PiActionGroup piGroup) {
        final PiActionGroupHandle handle = PiActionGroupHandle.of(deviceId, piGroup);
        final Optional<PiTranslatedEntity<Group, PiActionGroup>>
                translatedEntity = groupTranslator.lookup(handle);
        final TimedEntry<PiActionGroup> timedEntry = groupMirror.get(handle);
        // Is entry consistent with our state?
        if (!translatedEntity.isPresent()) {
            log.warn("Group handle not found in translation store: {}", handle);
            return null;
        }
        if (!translatedEntity.get().translated().equals(piGroup)) {
            log.warn("Group obtained from device {} is different from the one in" +
                             "translation store: device={}, store={}",
                     deviceId, piGroup, translatedEntity.get().translated());
            return null;
        }
        if (timedEntry == null) {
            log.warn("Group handle not found in device mirror: {}", handle);
            return null;
        }
        return addedGroup(translatedEntity.get().original(), timedEntry.lifeSec());
    }

    private Group addedGroup(Group original, long life) {
        final DefaultGroup forgedGroup = new DefaultGroup(original.id(), original);
        forgedGroup.setState(Group.GroupState.ADDED);
        forgedGroup.setLife(life);
        return forgedGroup;
    }

    private void processGroupOperation(Group pdGroup, GroupOperation.Type opType) {
        final PiActionGroup piGroup;
        try {
            piGroup = groupTranslator.translate(pdGroup, pipeconf);
        } catch (PiTranslationException e) {
            log.warn("Unable to translate group, aborting {} operation: {} [{}]",
                     opType, e.getMessage(), pdGroup);
            return;
        }
        final Operation operation = opType.equals(GroupOperation.Type.DELETE)
                ? Operation.REMOVE : Operation.APPLY;
        processGroup(piGroup, pdGroup, operation);
    }

    private void processGroup(PiActionGroup groupToApply,
                              Group pdGroup,
                              Operation operation) {
        final PiActionGroupHandle handle = PiActionGroupHandle.of(deviceId, groupToApply);
        STRIPED_LOCKS.get(handle).lock();
        try {
            switch (operation) {
                case APPLY:
                    if (applyGroupWithMembersOrNothing(groupToApply, handle)) {
                        groupTranslator.learn(handle, new PiTranslatedEntity<>(
                                pdGroup, groupToApply, handle));
                    }
                    return;
                case REMOVE:
                    if (deleteGroup(groupToApply, handle)) {
                        groupTranslator.forget(handle);
                    }
                    return;
                default:
                    log.error("Unknwon group operation type {}, cannot process group", operation);
                    break;
            }
        } finally {
            STRIPED_LOCKS.get(handle).unlock();
        }
    }

    private boolean applyGroupWithMembersOrNothing(PiActionGroup group, PiActionGroupHandle handle) {
        // First apply members, then group, if fails, delete members.
        if (!applyAllMembersOrNothing(group.members())) {
            return false;
        }
        if (!applyGroup(group, handle)) {
            deleteMembers(group.members());
            return false;
        }
        return true;
    }

    private boolean applyGroup(PiActionGroup group, PiActionGroupHandle handle) {
        final P4RuntimeClient.WriteOperationType opType =
                groupMirror.get(handle) == null ? INSERT : MODIFY;
        final boolean success = getFutureWithDeadline(
                client.writeActionGroup(group, opType, pipeconf),
                "performing action profile group " + opType, false);
        if (success) {
            groupMirror.put(handle, group);
        }
        return success;
    }

    private boolean deleteGroup(PiActionGroup group, PiActionGroupHandle handle) {
        final boolean success = getFutureWithDeadline(
                client.writeActionGroup(group, DELETE, pipeconf),
                "performing action profile group " + DELETE, false);
        if (success) {
            groupMirror.remove(handle);
        }
        return success;
    }

    private boolean applyAllMembersOrNothing(Collection<PiActionGroupMember> members) {
        Collection<PiActionGroupMember> appliedMembers = applyMembers(members);
        if (appliedMembers.size() == members.size()) {
            return true;
        } else {
            deleteMembers(appliedMembers);
            return false;
        }
    }

    private Collection<PiActionGroupMember> applyMembers(
            Collection<PiActionGroupMember> members) {
        return members.stream()
                .filter(this::applyMember)
                .collect(Collectors.toList());
    }

    private boolean applyMember(PiActionGroupMember member) {
        // If exists, modify, otherwise insert
        final PiActionGroupMemberHandle handle = PiActionGroupMemberHandle.of(
                deviceId, member);
        final P4RuntimeClient.WriteOperationType opType =
                memberMirror.get(handle) == null ? INSERT : MODIFY;
        final boolean success = getFutureWithDeadline(
                client.writeActionGroupMembers(Collections.singletonList(member),
                                               opType, pipeconf),
                "performing action profile member " + opType, false);
        if (success) {
            memberMirror.put(handle, dummyMember(member.actionProfile(), member.id()));
        }
        return success;
    }

    private void deleteMembers(Collection<PiActionGroupMember> members) {
        members.forEach(this::deleteMember);
    }

    private void deleteMember(PiActionGroupMember member) {
        final PiActionGroupMemberHandle handle = PiActionGroupMemberHandle.of(
                deviceId, member);
        final boolean success = getFutureWithDeadline(
                client.writeActionGroupMembers(Collections.singletonList(member),
                                               DELETE, pipeconf),
                "performing action profile member " + DELETE, false);
        if (success) {
            memberMirror.remove(handle);
        }
    }

    // FIXME: this is nasty, we have to rely on a dummy member of the mirror
    // because the PiActionGroupMember abstraction is broken, since it includes
    // attributes that are not part of a P4Runtime member, e.g. weight.
    // We should remove weight from the class, and have client methods that
    // return the full PiActionGroupMember, not just the IDs. Also the naming
    // "ActionGroupMember" is wrong since it makes believe that members can
    // exists only inside a group, which is not true.
    private PiActionGroupMember dummyMember(
            PiActionProfileId actionProfileId, PiActionGroupMemberId memberId) {
        return PiActionGroupMember.builder()
                .forActionProfile(actionProfileId)
                .withId(memberId)
                .withAction(PiAction.builder()
                                    .withId(PiActionId.of("dummy"))
                                    .build())
                .build();
    }

    enum Operation {
        APPLY, REMOVE
    }
}
