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

import com.facebook.buck.util.TriState;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Optional;

public class DefaultPerfTimer implements PerfTimer {
  private static TriState jvmSupportsCpuTime = TriState.UNSPECIFIED;
  private static Object lock = new Object();
  private static Optional<ThreadMXBean> bean = Optional.empty();

  private static boolean jvmSupportsCpuTime() {
    if (jvmSupportsCpuTime == TriState.UNSPECIFIED) {
      synchronized (lock) {
        if (jvmSupportsCpuTime == TriState.UNSPECIFIED) {
          bean = Optional.ofNullable(ManagementFactory.getThreadMXBean());
          jvmSupportsCpuTime = TriState.forBooleanValue(bean.isPresent() &&
                  bean.get().isThreadCpuTimeSupported());
          if (jvmSupportsCpuTime.asBoolean()) {
            bean.get().setThreadCpuTimeEnabled(true);
          }
        }
      }
    }
    return jvmSupportsCpuTime.asBoolean();
  }

  public DefaultPerfTimer() {
    jvmSupportsCpuTime();
  }

  @Override
  public AbsolutePerfTime getCurrentPerfTimeForThreadId(long threadId) {
    if (jvmSupportsCpuTime()) {
      long userCpuTime = bean.get().getThreadUserTime(threadId);
      long systemCpuTime = bean.get().getThreadCpuTime(threadId) - userCpuTime;
      return AbsolutePerfTime.of(userCpuTime, systemCpuTime);
    } else {
      return AbsolutePerfTime.of(AbsolutePerfTime.UNSUPPORTED, AbsolutePerfTime.UNSUPPORTED);
    }
  }
}
