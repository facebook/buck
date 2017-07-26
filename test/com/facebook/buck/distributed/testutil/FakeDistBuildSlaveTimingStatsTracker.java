/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed.testutil;

import com.facebook.buck.distributed.DistBuildSlaveTimingStatsTracker;
import java.util.HashMap;
import java.util.Map;

public class FakeDistBuildSlaveTimingStatsTracker extends DistBuildSlaveTimingStatsTracker {
  private Map<SlaveEvents, Long> elapsedTimeMillis = new HashMap<>();

  public void setElapsedTimeMillis(SlaveEvents event, long elapsedTimeMillis) {
    this.elapsedTimeMillis.put(event, elapsedTimeMillis);
  }

  @Override
  public long getElapsedTimeMs(SlaveEvents event) {
    if (elapsedTimeMillis.containsKey(event)) {
      return elapsedTimeMillis.get(event);
    } else {
      return 0;
    }
  }
}
