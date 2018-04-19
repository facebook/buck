/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.distributed.thrift.MinionType;
import com.google.common.base.Preconditions;
import java.util.HashMap;
import java.util.Map;

/** Provides a mapping between MinionType and corresponding minion queue */
public class MinionQueueProvider {
  private final Map<MinionType, String> minionTypeToQueueName = new HashMap<>();

  /** Register mapping between given minionType and minionQueue */
  public void registerMinionQueue(MinionType minionType, String minionQueue) {
    minionTypeToQueueName.put(minionType, minionQueue);
  }

  /** @return minion queue for the given minionType */
  public String getMinionQueue(MinionType minionType) {
    Preconditions.checkArgument(
        minionTypeToQueueName.containsKey(minionType),
        String.format(
            "No minion queue has been specified for minion type [%s]", minionType.name()));

    return Preconditions.checkNotNull(minionTypeToQueueName.get(minionType));
  }
}
