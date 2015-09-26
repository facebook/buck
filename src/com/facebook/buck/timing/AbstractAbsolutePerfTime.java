/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.timing;

import com.facebook.buck.util.immutables.BuckStyleImmutable;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractAbsolutePerfTime {
  public static final long UNSUPPORTED = -1;
  @Value.Parameter(order = 1)
  public abstract long getUserCpuTimeNs();
  @Value.Parameter(order = 2)
  public abstract long getSystemCpuTimeNs();

  public boolean hasCpuTime() { return getUserCpuTimeNs() != UNSUPPORTED; }
}
