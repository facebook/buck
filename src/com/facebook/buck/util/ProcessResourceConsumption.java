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

package com.facebook.buck.util;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import javax.annotation.Nullable;

/** Represents resource consumption counters of a {@link Process}. */
@BuckStyleValue
public abstract class ProcessResourceConsumption {

  public abstract long getMemResident();

  public abstract long getMemSize();

  public abstract long getCpuReal();

  public abstract long getCpuUser();

  public abstract long getCpuSys();

  public abstract long getCpuTotal();

  public abstract long getIoBytesRead();

  public abstract long getIoBytesWritten();

  public abstract long getIoTotal();

  @Nullable
  public static ProcessResourceConsumption getPeak(
      @Nullable ProcessResourceConsumption r1, @Nullable ProcessResourceConsumption r2) {
    if (r1 == null) {
      return r2;
    }
    if (r2 == null) {
      return r1;
    }
    return ImmutableProcessResourceConsumption.of(
        Math.max(r1.getMemResident(), r2.getMemResident()),
        Math.max(r1.getMemSize(), r2.getMemSize()),
        Math.max(r1.getCpuReal(), r2.getCpuReal()),
        Math.max(r1.getCpuUser(), r2.getCpuUser()),
        Math.max(r1.getCpuSys(), r2.getCpuSys()),
        Math.max(r1.getCpuTotal(), r2.getCpuTotal()),
        Math.max(r1.getIoBytesRead(), r2.getIoBytesRead()),
        Math.max(r1.getIoBytesWritten(), r2.getIoBytesWritten()),
        Math.max(r1.getIoTotal(), r2.getIoTotal()));
  }

  @Nullable
  public static ProcessResourceConsumption getTotal(
      @Nullable ProcessResourceConsumption r1, @Nullable ProcessResourceConsumption r2) {
    if (r1 == null) {
      return r2;
    }
    if (r2 == null) {
      return r1;
    }
    // For some stats such as cpu_real, parent's consumption already includes child's consumption,
    // so we just take max instead of sum to avoid double counting.
    return ImmutableProcessResourceConsumption.of(
        r1.getMemResident() + r2.getMemResident(),
        r1.getMemSize() + r2.getMemSize(),
        Math.max(r1.getCpuReal(), r2.getCpuReal()),
        r1.getCpuUser() + r2.getCpuUser(),
        r1.getCpuSys() + r2.getCpuSys(),
        r1.getCpuTotal() + r2.getCpuTotal(),
        r1.getIoBytesRead() + r2.getIoBytesRead(),
        r1.getIoBytesWritten() + r2.getIoBytesWritten(),
        r1.getIoTotal() + r2.getIoTotal());
  }

  public static ProcessResourceConsumption of(
      long memResident,
      long memSize,
      long cpuReal,
      long cpuUser,
      long cpuSys,
      long cpuTotal,
      long ioBytesRead,
      long ioBytesWritten,
      long ioTotal) {
    return ImmutableProcessResourceConsumption.of(
        memResident,
        memSize,
        cpuReal,
        cpuUser,
        cpuSys,
        cpuTotal,
        ioBytesRead,
        ioBytesWritten,
        ioTotal);
  }
}
