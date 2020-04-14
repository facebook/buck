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

package com.facebook.buck.support.jvm;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.lang.management.MemoryUsage;
import java.util.Map;

/**
 * Immutable wrapper class for {@link com.sun.management.GcInfo} to send information from {@link
 * GCNotificationEventEmitter}
 */
@BuckStyleValue
public abstract class GCNotificationInfo {
  public static GCNotificationInfo of(
      long id,
      long duration,
      Map<String, MemoryUsage> memoryUsageBeforeGC,
      Map<String, MemoryUsage> memoryUsageAfterGC) {
    return ImmutableGCNotificationInfo.ofImpl(
        id, duration, memoryUsageBeforeGC, memoryUsageAfterGC);
  }

  public abstract long getId();

  public abstract long getDuration();

  public abstract Map<String, MemoryUsage> getMemoryUsageBeforeGC();

  public abstract Map<String, MemoryUsage> getMemoryUsageAfterGC();
}
