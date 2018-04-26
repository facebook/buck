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
package com.facebook.buck.util.concurrent;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

@Value.Immutable(copy = true, singleton = true)
@BuckStyleImmutable
abstract class AbstractResourceAmounts {
  @Value.Parameter
  @Value.Default
  public int getCpu() {
    return 0;
  }

  @Value.Parameter
  @Value.Default
  public int getMemory() {
    return 0;
  }

  @Value.Parameter
  @Value.Default
  public int getDiskIO() {
    return 0;
  }

  @Value.Parameter
  @Value.Default
  public int getNetworkIO() {
    return 0;
  }

  /** If you add or remove resource types above please make sure you update the number below. */
  public static final int RESOURCE_TYPE_COUNT = 4;

  public static ResourceAmounts zero() {
    return ResourceAmounts.of();
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
