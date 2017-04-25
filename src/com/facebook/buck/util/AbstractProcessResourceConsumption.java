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
package com.facebook.buck.util;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** Represents resource consumption counters of a {@link Process}. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractProcessResourceConsumption {
  @Value.Parameter
  public abstract long getMemResident();

  @Value.Parameter
  public abstract long getMemSize();

  @Value.Parameter
  public abstract long getCpuReal();

  @Value.Parameter
  public abstract long getCpuUser();

  @Value.Parameter
  public abstract long getCpuSys();

  @Value.Parameter
  public abstract long getCpuTotal();

  @Value.Parameter
  public abstract long getIoBytesRead();

  @Value.Parameter
  public abstract long getIoBytesWritten();

  @Value.Parameter
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
    return ProcessResourceConsumption.builder()
        .setMemResident(Math.max(r1.getMemResident(), r2.getMemResident()))
        .setMemSize(Math.max(r1.getMemSize(), r2.getMemSize()))
        .setCpuReal(Math.max(r1.getCpuReal(), r2.getCpuReal()))
        .setCpuUser(Math.max(r1.getCpuUser(), r2.getCpuUser()))
        .setCpuSys(Math.max(r1.getCpuSys(), r2.getCpuSys()))
        .setCpuTotal(Math.max(r1.getCpuTotal(), r2.getCpuTotal()))
        .setIoBytesRead(Math.max(r1.getIoBytesRead(), r2.getIoBytesRead()))
        .setIoBytesWritten(Math.max(r1.getIoBytesWritten(), r2.getIoBytesWritten()))
        .setIoTotal(Math.max(r1.getIoTotal(), r2.getIoTotal()))
        .build();
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
    return ProcessResourceConsumption.builder()
        .setMemResident(r1.getMemResident() + r2.getMemResident())
        .setMemSize(r1.getMemSize() + r2.getMemSize())
        .setCpuReal(Math.max(r1.getCpuReal(), r2.getCpuReal()))
        .setCpuUser(r1.getCpuUser() + r2.getCpuUser())
        .setCpuSys(r1.getCpuSys() + r2.getCpuSys())
        .setCpuTotal(r1.getCpuTotal() + r2.getCpuTotal())
        .setIoBytesRead(r1.getIoBytesRead() + r2.getIoBytesRead())
        .setIoBytesWritten(r1.getIoBytesWritten() + r2.getIoBytesWritten())
        .setIoTotal(r1.getIoTotal() + r2.getIoTotal())
        .build();
  }
}
