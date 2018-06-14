/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.event.listener;

import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import javax.management.ObjectName;

class FakeThreadMXBean implements ThreadMXBean {

  @Override
  public int getThreadCount() {
    return 0;
  }

  @Override
  public int getPeakThreadCount() {
    return 0;
  }

  @Override
  public long getTotalStartedThreadCount() {
    return 0;
  }

  @Override
  public int getDaemonThreadCount() {
    return 0;
  }

  @Override
  public long[] getAllThreadIds() {
    return new long[0];
  }

  @Override
  public ThreadInfo getThreadInfo(long id) {
    return null;
  }

  @Override
  public ThreadInfo[] getThreadInfo(long[] ids) {
    return new ThreadInfo[0];
  }

  @Override
  public ThreadInfo getThreadInfo(long id, int maxDepth) {
    return null;
  }

  @Override
  public ThreadInfo[] getThreadInfo(long[] ids, int maxDepth) {
    return new ThreadInfo[0];
  }

  @Override
  public boolean isThreadContentionMonitoringSupported() {
    return false;
  }

  @Override
  public boolean isThreadContentionMonitoringEnabled() {
    return false;
  }

  @Override
  public void setThreadContentionMonitoringEnabled(boolean enable) {}

  @Override
  public long getCurrentThreadCpuTime() {
    return 0;
  }

  @Override
  public long getCurrentThreadUserTime() {
    return 0;
  }

  @Override
  public long getThreadCpuTime(long id) {
    return 0;
  }

  @Override
  public long getThreadUserTime(long id) {
    return 0;
  }

  @Override
  public boolean isThreadCpuTimeSupported() {
    return false;
  }

  @Override
  public boolean isCurrentThreadCpuTimeSupported() {
    return false;
  }

  @Override
  public boolean isThreadCpuTimeEnabled() {
    return false;
  }

  @Override
  public void setThreadCpuTimeEnabled(boolean enable) {}

  @Override
  public long[] findMonitorDeadlockedThreads() {
    return new long[0];
  }

  @Override
  public void resetPeakThreadCount() {}

  @Override
  public long[] findDeadlockedThreads() {
    return new long[0];
  }

  @Override
  public boolean isObjectMonitorUsageSupported() {
    return false;
  }

  @Override
  public boolean isSynchronizerUsageSupported() {
    return false;
  }

  @Override
  public ThreadInfo[] getThreadInfo(
      long[] ids, boolean lockedMonitors, boolean lockedSynchronizers) {
    return new ThreadInfo[0];
  }

  @Override
  public ThreadInfo[] dumpAllThreads(boolean lockedMonitors, boolean lockedSynchronizers) {
    return new ThreadInfo[0];
  }

  @Override
  public ObjectName getObjectName() {
    return null;
  }
}
