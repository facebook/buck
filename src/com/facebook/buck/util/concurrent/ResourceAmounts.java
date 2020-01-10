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

import com.facebook.buck.core.util.immutables.BuckStyleValue;

@BuckStyleValue
public abstract class ResourceAmounts {

  private static final ResourceAmounts ZERO = ImmutableResourceAmounts.of(0, 0, 0, 0);

  public abstract int getCpu();

  public abstract int getMemory();

  public abstract int getDiskIO();

  public abstract int getNetworkIO();

  /** If you add or remove resource types above please make sure you update the number below. */
  public static final int RESOURCE_TYPE_COUNT = 4;

  public static ResourceAmounts zero() {
    return ZERO;
  }

  public static ResourceAmounts of() {
    return ZERO;
  }

  public static ResourceAmounts of(int cpu, int memory, int diskIO, int networkIO) {
    if (cpu == 0 && memory == 0 && diskIO == 0 && networkIO == 0) {
      return ZERO;
    }
    return ImmutableResourceAmounts.of(cpu, memory, diskIO, networkIO);
  }

  public ResourceAmounts append(ResourceAmounts amounts) {
    return ResourceAmounts.of(
        getCpu() + amounts.getCpu(),
        getMemory() + amounts.getMemory(),
        getDiskIO() + amounts.getDiskIO(),
        getNetworkIO() + amounts.getNetworkIO());
  }

  public ResourceAmounts subtract(ResourceAmounts amounts) {
    return ResourceAmounts.of(
        getCpu() - amounts.getCpu(),
        getMemory() - amounts.getMemory(),
        getDiskIO() - amounts.getDiskIO(),
        getNetworkIO() - amounts.getNetworkIO());
  }

  public boolean containsValuesLessThan(ResourceAmounts amounts) {
    return getCpu() < amounts.getCpu()
        || getMemory() < amounts.getMemory()
        || getDiskIO() < amounts.getDiskIO()
        || getNetworkIO() < amounts.getNetworkIO();
  }

  public boolean allValuesLessThanOrEqual(ResourceAmounts amounts) {
    return getCpu() <= amounts.getCpu()
        && getMemory() <= amounts.getMemory()
        && getDiskIO() <= amounts.getDiskIO()
        && getNetworkIO() <= amounts.getNetworkIO();
  }
}
