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

package com.facebook.buck.util.timing;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/** {@link Clock} implementation that invokes the {@link System} calls. */
public class DefaultClock implements Clock {

  private final ThreadMXBean threadMXBean;
  private final boolean userNanoTimeEnabled;

  public DefaultClock() {
    this(true);
  }

  public DefaultClock(boolean enableThreadCpuTime) {
    threadMXBean = ManagementFactory.getThreadMXBean();
    userNanoTimeEnabled = enableThreadCpuTime && threadMXBean.isThreadCpuTimeEnabled();
  }

  @Override
  public long currentTimeMillis() {
    return System.currentTimeMillis();
  }

  @Override
  public long nanoTime() {
    return System.nanoTime();
  }

  @Override
  public long threadUserNanoTime(long threadId) {
    if (!userNanoTimeEnabled) {
      return -1;
    }
    return threadMXBean.getThreadUserTime(threadId);
  }
}
