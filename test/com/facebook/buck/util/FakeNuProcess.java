/*
 * Copyright 2014-present Facebook, Inc.
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

import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessHandler;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class FakeNuProcess implements NuProcess {
  final int pid;
  int exitCode;
  final AtomicBoolean running = new AtomicBoolean(true);

  public FakeNuProcess(int pid) {
    this.pid = pid;
  }

  @Override
  public int getPID() {
    return pid;
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  public void finish(int exitCode) {
    this.exitCode = exitCode;
    running.set(false);
  }

  @Override
  public int waitFor(long timeout, TimeUnit timeUnit) {
    running.set(false);
    return exitCode;
  }

  @Override
  public void wantWrite() {}

  @Override
  public void writeStdin(ByteBuffer buffer) {}

  @Override
  public void closeStdin(boolean force) {}

  @Override
  public boolean hasPendingWrites() {
    return false;
  }

  @Override
  public void destroy(boolean force) {}

  @Override
  public void setProcessHandler(NuProcessHandler processHandler) {}
}
