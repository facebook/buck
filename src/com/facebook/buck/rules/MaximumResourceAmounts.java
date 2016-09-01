/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.rules;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.util.concurrent.ResourceAmounts;
import com.facebook.buck.util.concurrent.ResourceAmountsEstimator;

/**
 * Uses estimator to obtain predefined resource amounts and allows them to be configured via config.
 */
public class MaximumResourceAmounts {
  private MaximumResourceAmounts() {}

  public static ResourceAmounts getMaximumAmounts(BuckConfig config) {
    ResourceAmounts estimated = ResourceAmountsEstimator.getEstimatedAmounts();
    return ResourceAmounts.of(
        config.getNumThreads(estimated.getCpu()),
        config.getInteger("build", "max_memory_resource").or(estimated.getMemory()),
        config.getInteger("build", "max_disk_io_resource").or(estimated.getDiskIO()),
        config.getInteger("build", "max_network_io_resource").or(estimated.getNetworkIO()));
  }
}
