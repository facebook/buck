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

package com.facebook.buck.cli;

import static com.facebook.buck.cli.ReplCommand.isNashornAvailable;
import static com.facebook.buck.cli.ReplCommand.runInterpreter;
import static com.facebook.buck.util.MoreStringsForTests.equalToIgnoringPlatformNewlines;
import static org.easymock.EasyMock.createMock;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.HashMap;

/**
 * Unit test for {@link ReplCommand}.
 */
public class ReplCommandIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void replWorksOnlyInInteractiveMode() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "repl_command", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("repl");
    result.assertSpecialExitCode("This is intended for interactive use.", 1);
  }

  @Test
  public void replCanAccessBuck() throws IOException, InterruptedException {
    // Nashorn has to be available to run this test.
    assumeTrue(isNashornAvailable());

    TestConsole console = new TestConsole();
    ByteArrayInputStream stdin = new ByteArrayInputStream((
        "java.lang.System.out.println(com.facebook.buck.cli.Main.FAIL_EXIT_CODE); quit();"
    ).getBytes());
    CommandRunnerParams params = createCommandRunnerParams(console, stdin);

    runInterpreter(params);

    assertThat(
        console.getTextWrittenToStdOut(),
        is(equalToIgnoringPlatformNewlines("Welcome to buck repl.\nThis is intended for " +
            "experimentation and debugging, any of the APIs may change without notice!\nYou " +
            "can exit with exit() or quit(). Help is available with help().\nbuck $ ")));
  }

  private CommandRunnerParams createCommandRunnerParams(TestConsole console, InputStream stdin)
      throws IOException, InterruptedException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem(Paths.get("/opt/foo"));
    Cell cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();

    Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier =
        AndroidPlatformTarget.EXPLODING_ANDROID_PLATFORM_TARGET_SUPPLIER;
    return new CommandRunnerParams(
        console,
        stdin,
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
        Optional.<ProcessManager>absent(),
        Optional.<WebServer>absent(),
        FakeBuckConfig.builder().build(),
        new NullFileHashCache(),
        new HashMap<ExecutionContext.ExecutorPool, ListeningExecutorService>(),
        CommandRunnerParamsForTesting.BUILD_ENVIRONMENT_DESCRIPTION,
        new ActionGraphCache());
  }
}
