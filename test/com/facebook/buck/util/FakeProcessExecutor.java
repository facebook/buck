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

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class FakeProcessExecutor extends ProcessExecutor {

  private final Function<ProcessExecutorParams, FakeProcess> processFunction;
  private final Set<ProcessExecutorParams> launchedProcesses;

  public FakeProcessExecutor() {
    this(ImmutableMap.<ProcessExecutorParams, FakeProcess>of());
  }

  public FakeProcessExecutor(Map<ProcessExecutorParams, FakeProcess> processMap) {
    this(processMap, new Console(Verbosity.ALL, System.out, System.err, Ansi.withoutTty()));
  }

  public FakeProcessExecutor(
      Iterable<Map.Entry<ProcessExecutorParams, FakeProcess>> processIterable) {
    this(processIterable, new Console(Verbosity.ALL, System.out, System.err, Ansi.withoutTty()));
  }

  public FakeProcessExecutor(
      final Iterable<Map.Entry<ProcessExecutorParams, FakeProcess>> processIterable,
      Console console) {
    this(
        new Function<ProcessExecutorParams, FakeProcess>() {
          final Iterator<Map.Entry<ProcessExecutorParams, FakeProcess>> processIterator =
              processIterable.iterator();

          @Override
          public FakeProcess apply(ProcessExecutorParams params) {
            Preconditions.checkState(
                processIterator.hasNext(),
                "Ran out of fake processes when asked to run %s",
                params);
            Map.Entry<ProcessExecutorParams, FakeProcess> nextProcess = processIterator.next();
            Preconditions.checkState(
                nextProcess.getKey().equals(params),
                "Mismatch when asked to run process %s (expecting %s)",
                params,
                nextProcess.getKey());
            return nextProcess.getValue();
          }
        },
        console);
  }

  public FakeProcessExecutor(
      Map<ProcessExecutorParams, FakeProcess> processMap,
      Console console) {
    this(Functions.forMap(processMap), console);
  }

  public FakeProcessExecutor(
      Function<ProcessExecutorParams, FakeProcess> processFunction,
      Console console) {
    super(console);
    this.processFunction = processFunction;
    this.launchedProcesses = new HashSet<>();
  }

  @Override
  Process launchProcessInternal(ProcessExecutorParams params) throws IOException {
    try {
      FakeProcess fakeProcess = processFunction.apply(params);
      launchedProcesses.add(params);
      return fakeProcess;
    } catch (IllegalArgumentException e) {
      throw new IOException(
          String.format(
              "FakeProcessExecutor not configured to run process with params %s",
              params));
    }
  }

  public boolean isProcessLaunched(ProcessExecutorParams params) {
    return launchedProcesses.contains(params);
  }
}
