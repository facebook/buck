/*
 * Copyright 2013-present Facebook, Inc.
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

import static java.util.concurrent.Executors.callable;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.module.impl.BuckModuleJarHashProvider;
import com.facebook.buck.core.module.impl.DefaultBuckModuleManager;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.io.watchman.WatchmanWatcher;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.environment.CommandMode;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.pf4j.PluginManager;

public class DaemonIntegrationTest {

  private ScheduledExecutorService executorService;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public TestWithBuckd testWithBuckd = new TestWithBuckd(tmp);

  @Before
  public void setUp() {
    executorService = Executors.newScheduledThreadPool(5);
  }

  @After
  public void tearDown() {
    Thread.interrupted(); // Clear interrupted flag, if set.
    executorService.shutdown();
  }

  /**
   * This verifies that when the user tries to run a read/write command, while another is already
   * running, the second call will fail. Serializing command execution in this way avoids multiple
   * threads accessing and corrupting the static state used by the Buck daemon and trampling over
   * each others output.
   */
  @Test
  public void whenConcurrentCommandExecutedThenSecondCommandFails()
      throws IOException, InterruptedException, ExecutionException {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "exclusive_execution", tmp);
    workspace.setUp();
    Future<?> firstThread =
        executorService.schedule(
            createRunnableCommand(ExitCode.SUCCESS, "build", "//:sleep"), 0, TimeUnit.MILLISECONDS);
    Future<?> secondThread =
        executorService.schedule(
            createRunnableCommand(ExitCode.BUSY, "build", "//:sleep"), 500L, TimeUnit.MILLISECONDS);
    firstThread.get();
    secondThread.get();
  }

  /**
   * Verifies that a client timeout will be detected by a Nailgun NGInputStream reading from a
   * blocking heartbeat stream.
   */
  @Test(expected = InterruptedException.class)
  public void whenClientTimeoutDetectedThenMainThreadIsInterrupted()
      throws InterruptedException, IOException {
    long timeoutMillis = 100;
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "exclusive_execution", tmp);
    workspace.setUp();

    // Build an NGContext connected to an NGInputStream reading from a stream that will timeout.
    Thread.currentThread().setName("Test");
    try (TestContext context =
        new TestContext(EnvVariablesProvider.getSystemEnv(), timeoutMillis)) {
      Thread thread = Thread.currentThread();
      context.addClientListener(
          reason -> {
            Threads.interruptThread(thread);
          });
      Thread.sleep(1000);
      fail("Should have been interrupted.");
    }
  }

  @Test
  public void whenConcurrentReadOnlyCommandExecutedThenReadOnlyCommandSucceeds()
      throws IOException, InterruptedException, ExecutionException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "exclusive_execution", tmp);
    workspace.setUp();
    Future<?> firstThread =
        executorService.schedule(
            createRunnableCommand(ExitCode.SUCCESS, "build", "//:sleep"), 0, TimeUnit.MILLISECONDS);
    Future<?> secondThread =
        executorService.schedule(
            createRunnableCommand(ExitCode.SUCCESS, "targets", "//..."),
            500L,
            TimeUnit.MILLISECONDS);
    firstThread.get();
    secondThread.get();
  }

  /** This verifies that multiple read only commands can be executed concurrently successfully. */
  @Test
  public void whenReadOnlyCommandsExecutedConcurrentlyThenAllSucceed()
      throws IOException, InterruptedException {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "exclusive_execution", tmp);
    workspace.setUp();
    executorService.invokeAll(
        ImmutableList.of(
            createCallableCommand(ExitCode.SUCCESS, "audit", "input", "//:sleep"),
            createCallableCommand(ExitCode.SUCCESS, "audit", "input", "//:sleep"),
            createCallableCommand(ExitCode.SUCCESS, "audit", "input", "//:sleep"),
            createCallableCommand(ExitCode.SUCCESS, "audit", "input", "//:sleep"),
            createCallableCommand(ExitCode.SUCCESS, "audit", "input", "//:sleep")));
  }

  private Runnable createRunnableCommand(ExitCode expectedExitCode, String... args) {
    return () -> {
      try {
        PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
        DefaultBuckModuleManager moduleManager =
            new DefaultBuckModuleManager(pluginManager, new BuckModuleJarHashProvider());

        MainRunner main =
            new MainRunner(
                new TestConsole(),
                new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)),
                new BuildId(),
                EnvVariablesProvider.getSystemEnv(),
                Platform.detect(),
                pluginManager,
                moduleManager,
                Optional.of(new TestContext()));
        ExitCode exitCode =
            main.runMainWithExitCode(
                tmp.getRoot(),
                CommandMode.TEST,
                WatchmanWatcher.FreshInstanceAction.NONE,
                System.nanoTime(),
                ImmutableList.copyOf(args));
        assertEquals("Unexpected exit code.", expectedExitCode, exitCode);
      } catch (InterruptedException e) {
        fail("Should not throw exception.");
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        fail("Should not throw exception.");
        Throwables.throwIfUnchecked(e);
      }
    };
  }

  private Callable<Object> createCallableCommand(ExitCode expectedExitCode, String... args) {
    return callable(createRunnableCommand(expectedExitCode, args));
  }

  @Test
  public void whenAppBuckFileRemovedThenRebuildFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckdCommand("build", "app");
    result.assertSuccess();

    String fileName = "apps/myapp/BUCK";
    Files.delete(workspace.getPath(fileName));

    workspace.runBuckdCommand("build", "app").assertExitCode(null, ExitCode.PARSE_ERROR);
  }

  @Test
  public void whenActivityBuckFileRemovedThenRebuildFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();
    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertSuccess();

    String fileName = "java/com/example/activity/BUCK";
    Files.delete(workspace.getPath(fileName));

    workspace
        .runBuckdCommand("build", "//java/com/example/activity:activity")
        .assertExitCode(null, ExitCode.PARSE_ERROR);
  }

  @Test
  public void whenSourceInputRemovedThenRebuildFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();
    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertSuccess();

    String fileName = "java/com/example/activity/MyFirstActivity.java";
    Files.delete(workspace.getPath(fileName));

    ProcessResult processResult =
        workspace.runBuckdCommand("build", "//java/com/example/activity:activity");
    processResult.assertFailure();
    assertThat(
        "Failure should have been due to file removal.",
        processResult.getStderr(),
        containsString("MyFirstActivity.java"));
  }

  @Test
  public void whenSourceInputInvalidatedThenRebuildFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();
    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertSuccess();

    String fileName = "java/com/example/activity/MyFirstActivity.java";
    Files.delete(workspace.getPath(fileName));

    ProcessResult processResult =
        workspace.runBuckdCommand("build", "//java/com/example/activity:activity");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("MyFirstActivity.java"));
  }

  @Test
  public void whenAppBuckFileInvalidatedThenRebuildFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();
    workspace.runBuckdCommand("build", "app").assertSuccess();

    String fileName = "apps/myapp/BUCK";
    Files.write(workspace.getPath(fileName), "Some Illegal Python".getBytes(Charsets.UTF_8));

    ProcessResult result = workspace.runBuckdCommand("build", "app");
    assertThat(
        "Failure should be due to syntax error.",
        result.getStderr(),
        containsString("Syntax error"));
    result.assertExitCode(null, ExitCode.PARSE_ERROR);
  }

  @Test
  public void whenNativeBuckTargetInvalidatedThenRebuildFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckdCommand("run", "//native/main:main");
    result.assertSuccess();
    assertThat(
        "Output should contain 'my_string_123_my_string'",
        result.getStdout(),
        containsString("my_string_123_my_string"));

    workspace.replaceFileContents("native/lib/BUCK", "123", "456");

    result = workspace.runBuckdCommand("run", "//native/main:main");
    result.assertSuccess();
    assertThat(
        "Output should contain 'my_string_456_my_string'",
        result.getStdout(),
        containsString("my_string_456_my_string"));
  }

  @Test
  public void whenNativeSourceInputInvalidatedThenRebuildFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckdCommand("run", "//native/main:main");
    result.assertSuccess();
    assertThat(
        "Output should contain 'my_string_123_my_string'",
        result.getStdout(),
        containsString("my_string_123_my_string"));

    workspace.replaceFileContents(
        "native/lib/lib.cpp", "THE_STRING", "\"my_string_456_my_string\"");

    result = workspace.runBuckdCommand("run", "//native/main:main");
    result.assertSuccess();
    assertThat(
        "Output should contain 'my_string_456_my_string'",
        result.getStdout(),
        containsString("my_string_456_my_string"));
  }

  @Test
  public void whenCrossCellSourceInvalidatedThenRebuildFails() throws IOException {
    ProjectWorkspace primary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_file_watching/primary", tmp.newFolder());
    primary.setUp();
    ProjectWorkspace secondary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_file_watching/secondary", tmp.newFolder());
    secondary.setUp();
    TestDataHelper.overrideBuckconfig(
        primary,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of("secondary", secondary.getPath(".").normalize().toString())));
    primary.runBuckdCommand("build", "//:cxxbinary").assertSuccess();
    ProcessResult result = primary.runBuckdCommand("run", "//:cxxbinary");
    result.assertSuccess();

    String fileName = "sum.cpp";
    Files.write(secondary.getPath(fileName), "#error Failure".getBytes(Charsets.UTF_8));

    result = primary.runBuckdCommand("build", "//:cxxbinary");
    assertThat(
        "Failure should be due to compilation error.",
        result.getStderr(),
        containsString("#error Failure"));
    result.assertFailure();

    // Make the file valid again, but change the output
    Files.write(
        secondary.getPath(fileName),
        "#include \"sum.hpp\"\nint sum(int a, int b) {return a;}".getBytes(Charsets.UTF_8));

    primary.runBuckdCommand("build", "//:cxxbinary").assertSuccess();
    result = primary.runBuckdCommand("run", "//:cxxbinary");
    result.assertFailure();
  }

  @Test
  public void whenCrossCellBuckFileInvalidatedThenRebuildFails() throws IOException {
    ProjectWorkspace primary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_file_watching/primary", tmp.newFolder());
    primary.setUp();
    ProjectWorkspace secondary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_file_watching/secondary", tmp.newFolder());
    secondary.setUp();
    TestDataHelper.overrideBuckconfig(
        primary,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of("secondary", secondary.getPath(".").normalize().toString())));
    primary.runBuckdCommand("build", "//:cxxbinary").assertSuccess();

    String fileName = "BUCK";
    Files.write(secondary.getPath(fileName), "Some Invalid Python".getBytes(Charsets.UTF_8));

    ProcessResult processResult = primary.runBuckdCommand("build", "//:cxxbinary");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "This error happened while trying to get dependency 'secondary//:cxxlib' of target '//:cxxbinary'"));
  }

  @Test
  public void whenBuckBuiltTwiceLogIsPresent() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "file_watching", tmp);
    workspace.setUp();

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertSuccess();

    Path buildLogFile = workspace.getPath("buck-out/bin/build.log");

    assertTrue(Files.isRegularFile(buildLogFile));
    assertThat(Files.readAllLines(buildLogFile), hasItem(containsString("BUILT_LOCALLY")));
    Files.delete(buildLogFile);

    ProcessResult rebuild =
        workspace.runBuckdCommand("build", "//java/com/example/activity:activity");
    rebuild.assertSuccess();

    buildLogFile = workspace.getPath("buck-out/bin/build.log");
    assertTrue(Files.isRegularFile(buildLogFile));
    assertThat(Files.readAllLines(buildLogFile), not(hasItem(containsString("BUILT_LOCALLY"))));
  }

  @Test
  public void whenNativeTargetBuiltTwiceCacheHits() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_file_watching/primary", tmp);
    workspace.setUp();

    ProjectWorkspace secondary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_file_watching/secondary", tmp.newFolder());
    secondary.setUp();
    TestDataHelper.overrideBuckconfig(
        workspace,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of("secondary", secondary.getPath(".").normalize().toString())));

    workspace.runBuckdCommand("build", "//:cxxbinary").assertSuccess();

    Path buildLogFile = workspace.getPath("buck-out/bin/build.log");
    assertTrue(Files.isRegularFile(buildLogFile));
    assertThat(Files.readAllLines(buildLogFile), hasItem(containsString("BUILT_LOCALLY")));
    Files.delete(buildLogFile);

    workspace.runBuckdCommand("build", "//:cxxbinary").assertSuccess();
    buildLogFile = workspace.getPath("buck-out/bin/build.log");
    assertTrue(Files.isRegularFile(buildLogFile));
    assertThat(Files.readAllLines(buildLogFile), not(hasItem(containsString("BUILT_LOCALLY"))));
  }

  @Test
  public void crossCellIncludeDefChangesInvalidateBuckTargets() throws Exception {
    ProjectWorkspace primary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_include_defs/primary", tmp.newFolder("primary"));
    primary.setUp();

    ProjectWorkspace secondary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_include_defs/secondary", tmp.newFolder("secondary"));
    secondary.setUp();
    TestDataHelper.overrideBuckconfig(
        primary,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of("secondary", secondary.getPath(".").normalize().toString())));

    primary.runBuckdCommand("build", ":rule").assertSuccess();
    Files.write(secondary.getPath("included_by_primary.py"), new byte[] {});
    primary.runBuckdCommand("build", ":rule").assertExitCode(null, ExitCode.PARSE_ERROR);
  }
}
