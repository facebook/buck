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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.listener.BroadcastEventListener;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.jvm.java.intellij.Project;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.easymock.EasyMockSupport;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Optional;

/**
 * Unit test for {@link CleanCommand}.
 */
public class CleanCommandTest extends EasyMockSupport {

  private ProjectFilesystem projectFilesystem;

  // TODO(bolinfest): When it is possible to inject a mock object for stderr,
  // create a test that runs `buck clean unexpectedarg` and verify that the
  // exit code is 1 and that the appropriate error message is printed.

  @Test
  public void testCleanCommandNoArguments()
      throws CmdLineException, IOException, InterruptedException {
    CommandRunnerParams params = createCommandRunnerParams();

    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getScratchDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getGenDir());
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getTrashDir());

    // Simulate `buck clean`.
    CleanCommand cleanCommand = createCommandFromArgs();
    int exitCode = cleanCommand.run(params);
    assertEquals(0, exitCode);

    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getScratchDir()));
    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getGenDir()));
    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getTrashDir()));
  }

  @Test
  public void testCleanCommandWithProjectArgument()
      throws CmdLineException, IOException, InterruptedException {
    CommandRunnerParams params = createCommandRunnerParams();

    // Set up mocks.
    projectFilesystem.mkdirs(Project.getAndroidGenPath(projectFilesystem));
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getAnnotationDir());

    // Simulate `buck clean --project`.
    CleanCommand cleanCommand = createCommandFromArgs("--project");
    int exitCode = cleanCommand.run(params);
    assertEquals(0, exitCode);

    assertFalse(projectFilesystem.exists(Project.getAndroidGenPath(projectFilesystem)));
    assertFalse(projectFilesystem.exists(projectFilesystem.getBuckPaths().getAnnotationDir()));
  }

  private CleanCommand createCommandFromArgs(String... args) throws CmdLineException {
    CleanCommand command = new CleanCommand();
    new AdditionalOptionsCmdLineParser(command).parseArgument(args);
    return command;
  }

  private CommandRunnerParams createCommandRunnerParams() throws InterruptedException, IOException {
    projectFilesystem = new FakeProjectFilesystem();

    Cell cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();

    Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier =
        AndroidPlatformTarget.EXPLODING_ANDROID_PLATFORM_TARGET_SUPPLIER;
    return new CommandRunnerParams(
        new TestConsole(),
        new ByteArrayInputStream("".getBytes("UTF-8")),
        cell,
        androidPlatformTargetSupplier,
        createMock(ArtifactCache.class),
        BuckEventBusFactory.newInstance(),
        createMock(Parser.class),
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()),
        new FakeJavaPackageFinder(),
        ObjectMappers.newDefaultInstance(),
        new DefaultClock(),
        Optional.empty(),
        Optional.empty(),
        FakeBuckConfig.builder().build(),
        new NullFileHashCache(),
        new HashMap<ExecutorPool, ListeningExecutorService>(),
        CommandRunnerParamsForTesting.BUILD_ENVIRONMENT_DESCRIPTION,
        new ActionGraphCache(new BroadcastEventListener()));
  }

}
