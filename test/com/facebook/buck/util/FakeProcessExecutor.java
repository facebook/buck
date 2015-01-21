/*
 * Copyright 2012-present Facebook, Inc.
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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FakeProcessExecutor extends ProcessExecutor {

  private final ImmutableMap<ProcessExecutorParams, FakeProcess> processMap;
  private final Set<ProcessExecutorParams> launchedProcesses;

  public FakeProcessExecutor() {
    this(ImmutableMap.<ProcessExecutorParams, FakeProcess>of());
  }

  public FakeProcessExecutor(Map<ProcessExecutorParams, FakeProcess> processMap) {
    this(processMap, new Console(Verbosity.ALL, System.out, System.err, Ansi.withoutTty()));
  }

  public FakeProcessExecutor(
      Map<ProcessExecutorParams, FakeProcess> processMap,
      Console console) {
    super(console);
    this.processMap = ImmutableMap.copyOf(processMap);
    this.launchedProcesses = new HashSet<>();
  }

  @Override
  Process launchProcess(ProcessExecutorParams params) throws IOException {
    Preconditions.checkArgument(processMap.keySet().contains(params), params.toString());
    FakeProcess fakeProcess = Preconditions.checkNotNull(processMap.get(params));
    launchedProcesses.add(params);
    return fakeProcess;
  }

  public boolean isProcessLaunched(ProcessExecutorParams params) {
    return launchedProcesses.contains(params);
  }
}
