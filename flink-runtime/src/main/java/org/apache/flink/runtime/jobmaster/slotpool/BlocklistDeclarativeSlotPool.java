/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.jobmaster.slotpool;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.blocklist.BlockedTaskManagerChecker;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.jobmanager.slots.TaskManagerGateway;
import org.apache.flink.runtime.jobmaster.SlotInfo;
import org.apache.flink.runtime.slots.ResourceRequirement;
import org.apache.flink.runtime.taskexecutor.slot.SlotOffer;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation;
import org.apache.flink.runtime.util.ResourceCounter;
import org.apache.flink.util.FlinkRuntimeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Consumer;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * A {@link DeclarativeSlotPool} implementation that supports blocklist. This implementation will
 * avoid allocating slots that located on blocked nodes. The core idea is to keep the slot pool in
 * such a state: there is no slot in slot pool that is free (no task assigned) and located on
 * blocked nodes.
 */
public class BlocklistDeclarativeSlotPool extends DefaultDeclarativeSlotPool {

    private static final Logger LOG = LoggerFactory.getLogger(BlocklistDeclarativeSlotPool.class);

    private final BlockedTaskManagerChecker blockedTaskManagerChecker;

    BlocklistDeclarativeSlotPool(
            JobID jobId,
            AllocatedSlotPool slotPool,
            Consumer<? super Collection<ResourceRequirement>> notifyNewResourceRequirements,
            BlockedTaskManagerChecker blockedTaskManagerChecker,
            Time idleSlotTimeout,
            Time rpcTimeout) {
        super(jobId, slotPool, notifyNewResourceRequirements, idleSlotTimeout, rpcTimeout);
        this.blockedTaskManagerChecker = checkNotNull(blockedTaskManagerChecker);
    }

    @Override
    public Collection<SlotOffer> offerSlots(
            Collection<? extends SlotOffer> offers,
            TaskManagerLocation taskManagerLocation,
            TaskManagerGateway taskManagerGateway,
            long currentTime) {
        if (!isBlockedTaskManager(taskManagerLocation.getResourceID())) {
            return super.offerSlots(offers, taskManagerLocation, taskManagerGateway, currentTime);
        } else {
            LOG.debug(
                    "Reject slots {} from a blocked TaskManager {}.", offers, taskManagerLocation);
            return Collections.emptySet();
        }
    }

    @Override
    public Collection<SlotOffer> registerSlots(
            Collection<? extends SlotOffer> slots,
            TaskManagerLocation taskManagerLocation,
            TaskManagerGateway taskManagerGateway,
            long currentTime) {
        if (!isBlockedTaskManager(taskManagerLocation.getResourceID())) {
            return super.registerSlots(slots, taskManagerLocation, taskManagerGateway, currentTime);
        } else {
            LOG.debug("Reject slots {} from a blocked TaskManager {}.", slots, taskManagerLocation);
            return Collections.emptySet();
        }
    }

    @Override
    public ResourceCounter freeReservedSlot(
            AllocationID allocationId, @Nullable Throwable cause, long currentTime) {
        Optional<SlotInfo> slotInfo = slotPool.getSlotInformation(allocationId);

        if (!slotInfo.isPresent()) {
            return ResourceCounter.empty();
        }

        ResourceID taskManagerId = slotInfo.get().getTaskManagerLocation().getResourceID();
        if (!isBlockedTaskManager(taskManagerId)) {
            return super.freeReservedSlot(allocationId, cause, currentTime);
        } else {
            LOG.debug("Free reserved slot {}.", allocationId);
            return releaseSlot(
                    allocationId,
                    new FlinkRuntimeException(
                            String.format(
                                    "Free reserved slot %s on blocked task manager %s.",
                                    allocationId, taskManagerId.getStringWithMetadata())));
        }
    }

    private boolean isBlockedTaskManager(ResourceID resourceID) {
        return blockedTaskManagerChecker.isBlockedTaskManager(resourceID);
    }
}
