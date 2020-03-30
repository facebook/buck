/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.util.concurrent;

public class ResourceAmountsEstimator {

  /** CPU resource amount is considered as number of cores. Each core can perform a single job. */
  public static final int DEFAULT_CPU_CAP = Runtime.getRuntime().availableProcessors();

  /** Memory resource unit size has been chosen arbitrarily. We can tune the value if we need. */
  public static final int DEFAULT_MEMORY_CAP =
      (int) (Runtime.getRuntime().maxMemory() / (100 * 1024 * 1024));

  /**
   * Disk IO resource unit size has been chosen arbitrarily. Since most of the jobs are light, we
   * think disk can handle this amount of light jobs: reading/writing some small data.
   */
  public static final int DEFAULT_DISK_IO_CAP = 50;

  /**
   * Network IO resource unit size has been chosen arbitrarily. Since most of the jobs are light, we
   * think network can handle this amount of light jobs: sending/receiving some small data.
   */
  public static final int DEFAULT_NETWORK_IO_CAP = 30;

  public static final ResourceAmounts DEFAULT_MAXIMUM_AMOUNTS =
      ResourceAmounts.of(
          DEFAULT_CPU_CAP, DEFAULT_MEMORY_CAP, DEFAULT_DISK_IO_CAP, DEFAULT_NETWORK_IO_CAP);

  /** Total number of threads Buck can use to schedule various work. */
  public static final int DEFAULT_MANAGED_THREAD_COUNT = DEFAULT_CPU_CAP * 2;

  private ResourceAmountsEstimator() {}

  public static ResourceAmounts getEstimatedAmounts() {
    return ResourceAmounts.of(
        DEFAULT_CPU_CAP, DEFAULT_MEMORY_CAP, DEFAULT_DISK_IO_CAP, DEFAULT_NETWORK_IO_CAP);
  }

  public static final int DEFAULT_CPU_AMOUNT = 1;
  public static final int DEFAULT_MEMORY_AMOUNT = 1;
  public static final int DEFAULT_DISK_IO_AMOUNT = 0;
  public static final int DEFAULT_NETWORK_IO_AMOUNT = 0;
  public static final ResourceAmounts DEFAULT_AMOUNTS =
      ResourceAmounts.of(
          DEFAULT_CPU_AMOUNT,
          DEFAULT_MEMORY_AMOUNT,
          DEFAULT_DISK_IO_AMOUNT,
          DEFAULT_NETWORK_IO_AMOUNT);
}
