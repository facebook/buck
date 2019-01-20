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

package com.facebook.buck.cli;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.FakeListeningProcessExecutor;
import com.facebook.buck.util.FakeListeningProcessState;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.logging.Level;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class BuildPrehookTest {

  private Collection<FakeListeningProcessState> processStates;
  private FakeListeningProcessExecutor processExecutor;
  private Cell cell;
  private BuckEventBus eventBus;
  private BuckConfig buckConfig;
  private FakeBuckEventListener eventListener;
  private ProcessExecutorParams params;

  @Before
  public void setUp() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    cell = new TestCellBuilder().setFilesystem(filesystem).build();
    String pathToScript =
        cell.getFilesystem().getPathForRelativePath("script.sh").toAbsolutePath().toString();
    buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("build", ImmutableMap.of("prehook_script", pathToScript)))
            .build();

    processExecutor =
        new FakeListeningProcessExecutor(
            params -> {
              this.params = params;
              return processStates;
            },
            SettableFakeClock.DO_NOT_CARE);

    eventBus = BuckEventBusForTests.newInstance();
    eventListener = new FakeBuckEventListener();
    eventBus.register(eventListener);
  }

  @Test
  public void presubmitHookPostsAWarningOnStderr() throws Exception {
    String warningMessage = "some_warning";
    FakeListeningProcessState stderrState =
        FakeListeningProcessState.builder()
            .setStderr(StandardCharsets.UTF_8.encode(warningMessage))
            .setType(FakeListeningProcessState.Type.STDERR)
            .build();
    FakeListeningProcessState exitState = FakeListeningProcessState.ofExit(0);
    processStates = Arrays.asList(stderrState, exitState);

    try (BuildPrehook buildPrehook = newBuildHook()) {
      buildPrehook.startPrehookScript();
      processExecutor.waitForAllLaunchedProcesses();
    }
    ConsoleEvent warning = (ConsoleEvent) Iterables.getOnlyElement(eventListener.getEvents());
    assertThat(warning.getLevel(), CoreMatchers.equalTo(Level.WARNING));
    assertThat(warning.getMessage(), CoreMatchers.equalTo(warningMessage));
  }

  @Test
  public void presubmitHookPostsNothingOnNoStdErr() throws Exception {
    processStates = Collections.singleton(FakeListeningProcessState.ofExit(0));

    try (BuildPrehook buildPrehook = newBuildHook()) {
      buildPrehook.startPrehookScript();
      processExecutor.waitForAllLaunchedProcesses();
    }
    assertThat(eventListener.getEvents(), Matchers.empty());
  }

  @Test
  public void buildArgumentsArePassed() throws Exception {
    processStates = Collections.singleton(FakeListeningProcessState.ofExit(0));

    try (BuildPrehook buildPrehook = newBuildHook(ImmutableList.of("target"))) {
      buildPrehook.startPrehookScript();
      processExecutor.waitForAllLaunchedProcesses();
      String argumentsFile = params.getEnvironment().get().get("BUCK_BUILD_ARGUMENTS_FILE");
      String argumentsJson = Iterables.getOnlyElement(Files.readAllLines(Paths.get(argumentsFile)));
      assertThat(argumentsJson, Matchers.equalTo("[ \"target\" ]"));
    }
  }

  private BuildPrehook newBuildHook() {
    return newBuildHook(ImmutableList.of());
  }

  private BuildPrehook newBuildHook(ImmutableList<String> arguments) {
    ImmutableMap<String, String> env = ImmutableMap.of();
    return new BuildPrehook(processExecutor, cell, eventBus, buckConfig, env, arguments);
  }
}
