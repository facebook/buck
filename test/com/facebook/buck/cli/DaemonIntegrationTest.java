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

import static com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import static java.util.concurrent.Executors.callable;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DelegatingInputStream;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.timing.FakeClock;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.WatchmanWatcher;
import com.facebook.buck.util.environment.CommandMode;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DaemonIntegrationTest {

  private static final int SUCCESS_EXIT_CODE = 0;
  private ScheduledExecutorService executorService;

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private static ImmutableMap<String, String> getWatchmanEnv() {
    ImmutableMap.Builder<String, String> envBuilder = ImmutableMap.builder();
    String systemPath = System.getenv("PATH");
    if (systemPath != null) {
      envBuilder.put("PATH", systemPath);
    }
    return envBuilder.build();
  }

  @Before
  public void setUp() throws IOException, InterruptedException{
    executorService = Executors.newScheduledThreadPool(5);
    // In case root_restrict_files is enabled in /etc/watchmanconfig, assume
    // this is one of the entries so it doesn't give up.
    tmp.newFolder(".git");
    tmp.newFile(".arcconfig");
    Watchman watchman = Watchman.build(
        ImmutableSet.of(tmp.getRoot()),
        getWatchmanEnv(),
        new TestConsole(),
        new FakeClock(0),
        Optional.empty());

    // We assume watchman has been installed and configured properly on the system, and that setting
    // up the watch is successful.
    assumeFalse(watchman == Watchman.NULL_WATCHMAN);
  }

  @After
  public void tearDown() {
    Thread.interrupted(); // Clear interrupted flag, if set.
    executorService.shutdown();
    Main.resetDaemon();
  }

  /**
   * This verifies that when the user tries to run a read/write command, while another is already
   * running, the second call will fail. Serializing command execution in this way avoids
   * multiple threads accessing and corrupting the static state used by the Buck daemon and
   * trampling over each others output.
   */
  @Test
  public void whenConcurrentCommandExecutedThenSecondCommandFails()
      throws IOException, InterruptedException, ExecutionException {

    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "exclusive_execution", tmp);
    workspace.setUp();
    Future<?> firstThread = executorService.schedule(
        createRunnableCommand(SUCCESS_EXIT_CODE, "build", "//:sleep"),
        0,
        TimeUnit.MILLISECONDS);
    Future<?> secondThread = executorService.schedule(
        createRunnableCommand(Main.BUSY_EXIT_CODE, "build", "//:sleep"),
        500L,
        TimeUnit.MILLISECONDS);
    firstThread.get();
    secondThread.get();
  }

  /**
   * Verifies that a client timeout will be detected by a Nailgun
   * NGInputStream reading from a blocking heartbeat stream.
   */
  @Test(expected = InterruptedException.class)
  public void whenClientTimeoutDetectedThenMainThreadIsInterrupted()
      throws InterruptedException, IOException {
    final long timeoutMillis = 100;
    final long intervalMillis = timeoutMillis * 2; // Interval > timeout to trigger disconnection.
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "exclusive_execution", tmp);
    workspace.setUp();

    // Build an NGContext connected to an NGInputStream reading from a stream that will timeout.
    Thread.currentThread().setName("Test");
    try (TestContext context = new TestContext(
        ImmutableMap.copyOf(System.getenv()),
        TestContext.createHeartBeatStream(intervalMillis),
        timeoutMillis)) {
      context.addClientListener(Thread.currentThread()::interrupt);
      Thread.sleep(1000);
      fail("Should have been interrupted.");
    }
  }

  /**
   * This verifies that a client timeout will be detected by a Nailgun
   * NGInputStream reading from an empty heartbeat stream and that the generated
   * InterruptedException will cause command execution to fail after timeout.
   */
  @Test
  public void whenClientTimeoutDetectedThenBuildIsInterrupted()
      throws InterruptedException, IOException {

    // Sub process interruption not supported on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    final long timeoutMillis = 100;
    final long intervalMillis = timeoutMillis * 2; // Interval > timeout to trigger disconnection.
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "exclusive_execution", tmp);
    workspace.setUp();

    // Build an NGContext connected to an NGInputStream reading from stream that will timeout.
    try (TestContext context = new TestContext(
        ImmutableMap.copyOf(System.getenv()),
        TestContext.createHeartBeatStream(intervalMillis),
        timeoutMillis)) {
      ProcessResult result = workspace.runBuckdCommand(context, "build", "//:sleep");
      result.assertFailure();
      assertThat(result.getStderr(), containsString("InterruptedException"));
    }
  }

  @Test
  public void whenConcurrentReadOnlyCommandExecutedThenReadOnlyCommandSucceeds()
      throws IOException, InterruptedException, ExecutionException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "exclusive_execution", tmp);
    workspace.setUp();
    Future<?> firstThread = executorService.schedule(
        createRunnableCommand(SUCCESS_EXIT_CODE, "build", "//:sleep"), 0, TimeUnit.MILLISECONDS);
    Future<?> secondThread = executorService.schedule(
        createRunnableCommand(SUCCESS_EXIT_CODE, "targets"), 500L, TimeUnit.MILLISECONDS);
    firstThread.get();
    secondThread.get();
  }

  /**
   * This verifies that multiple read only commands can be executed concurrently successfully.
   */
  @Test
  public void whenReadOnlyCommandsExecutedConcurrentlyThenAllSucceed()
      throws IOException, InterruptedException, ExecutionException {

    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "exclusive_execution", tmp);
    workspace.setUp();
    executorService.invokeAll(
        ImmutableList.of(
            createCallableCommand(SUCCESS_EXIT_CODE, "audit", "input", "//:sleep"),
            createCallableCommand(SUCCESS_EXIT_CODE, "audit", "input", "//:sleep"),
            createCallableCommand(SUCCESS_EXIT_CODE, "audit", "input", "//:sleep"),
            createCallableCommand(SUCCESS_EXIT_CODE, "audit", "input", "//:sleep"),
            createCallableCommand(SUCCESS_EXIT_CODE, "audit", "input", "//:sleep"))
    );
  }

  private Runnable createRunnableCommand(final int expectedExitCode, final String ... args) {
    return () -> {
      try {
        Main main = new Main(
            new CapturingPrintStream(),
            new CapturingPrintStream(),
            new ByteArrayInputStream("".getBytes("UTF-8")));
        int exitCode = main.runMainWithExitCode(
            new BuildId(),
            tmp.getRoot(),
            Optional.of(new TestContext()),
            ImmutableMap.copyOf(System.getenv()),
            CommandMode.TEST,
            WatchmanWatcher.FreshInstanceAction.NONE,
            args);
        assertEquals("Unexpected exit code.", expectedExitCode, exitCode);
      } catch (IOException e) {
        fail("Should not throw exception.");
        throw Throwables.propagate(e);
      } catch (InterruptedException e) {
        fail("Should not throw exception.");
        Thread.currentThread().interrupt();
      }
    };
  }

  private Callable<Object> createCallableCommand(int expectedExitCode, String ... args) {
    return callable(createRunnableCommand(expectedExitCode, args));
  }

  /**
   * This verifies that a client disconnection will be detected by a Nailgun
   * NGInputStream reading from an empty heartbeat stream and that the generated
   * InterruptedException will interrupt command execution causing it to fail.
   */
  @Test
  public void whenClientTimeoutDetectedThenTestIsInterrupted()
      throws InterruptedException, IOException {

    // Sub process interruption not supported on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    final long timeoutMillis = 100;
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "exclusive_execution", tmp);
    workspace.setUp();

    // Start with an input stream that sends heartbeats at a regular rate.
    final DelegatingInputStream inputStream = new DelegatingInputStream(
        TestContext.createHeartBeatStream(timeoutMillis / 10));

    // Build an NGContext connected to an NGInputStream reading from stream that will timeout.
    try (TestContext context = new TestContext(
        ImmutableMap.copyOf(System.getenv()),
        inputStream,
        timeoutMillis)) {
      ProcessResult result = workspace.runBuckdCommand(
          context,
          new CapturingPrintStream() {
            @Override
            public void println(String x) {
              if (x.contains("TESTING //:test")) {
                // When tests start running, make the heartbeat stream simulate a disconnection.
                inputStream.setDelegate(TestContext.createDisconnectionStream(2 * timeoutMillis));
              }
              super.println(x);
            }
          },
          "test",
          "//:test");
      result.assertFailure();
      assertThat(result.getStderr(), containsString("InterruptedException"));
    }
  }

  /**
   * This verifies that a client timeout will be detected by a Nailgun
   * NGInputStream reading from an empty heartbeat stream and that the generated
   * InterruptedException will cause command execution to fail after timeout.
   */
  @Test
  public void whenClientDisconnectionDetectedThenBuildIsInterrupted()
      throws InterruptedException, IOException {

    // Sub process interruption not supported on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    final long timeoutMillis = 2000; // Stream timeout > test timeout.
    final long disconnectMillis = 100; // Disconnect before test timeout.
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "exclusive_execution", tmp);
    workspace.setUp();

    // Build an NGContext connected to an NGInputStream reading from stream that will timeout.
    try (TestContext context = new TestContext(
        ImmutableMap.copyOf(System.getenv()),
        TestContext.createDisconnectionStream(disconnectMillis),
        timeoutMillis)) {
      ProcessResult result = workspace.runBuckdCommand(context, "build", "//:sleep");
      result.assertFailure();
      assertThat(result.getStderr(), containsString("InterruptedException"));
    }
  }

  /**
   * This verifies that a client disconnection will be detected by a Nailgun
   * NGInputStream reading from an empty heartbeat stream and that the generated
   * InterruptedException will interrupt command execution causing it to fail.
   */
  @Test
  public void whenClientDisconnectionDetectedThenTestIsInterrupted()
      throws InterruptedException, IOException {

    // Sub process interruption not supported on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    final long timeoutMillis = 2000; // Stream timeout > test timeout.
    final long disconnectMillis = 100; // Disconnect before test timeout.
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "exclusive_execution", tmp);
    workspace.setUp();

    // Start with an input stream that sends heartbeats at a regular rate.
    final DelegatingInputStream inputStream = new DelegatingInputStream(
        TestContext.createHeartBeatStream(timeoutMillis / 10));

    // Build an NGContext connected to an NGInputStream reading from stream that will timeout.
    try (TestContext context = new TestContext(
        ImmutableMap.copyOf(System.getenv()),
        inputStream,
        timeoutMillis)) {
      ProcessResult result = workspace.runBuckdCommand(
          context,
          new CapturingPrintStream() {
            @Override
            public void println(String x) {
              if (x.contains("TESTING //:test")) {
                // When tests start running, make the heartbeat stream simulate a disconnection.
                inputStream.setDelegate(TestContext.createDisconnectionStream(disconnectMillis));
              }
              super.println(x);
            }
          },
          "test",
          "//:test");
      result.assertFailure();
      assertThat(result.getStderr(), containsString("InterruptedException"));
    }
  }

  private void whenAppBuckFileRemovedThenRebuildFails(String cursorType)
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();
    TestDataHelper.overrideBuckconfig(
        workspace,
        ImmutableMap.of("project", ImmutableMap.of("watchman_cursor", cursorType)));

    ProcessResult result = workspace.runBuckdCommand("build", "app");
    result.assertSuccess();

    String fileName = "apps/myapp/BUCK";
    Files.delete(workspace.getPath(fileName));

    workspace.runBuckdCommand("build", "app").assertFailure();
  }

  @Test
  public void withNamedCursorAppBuckFileRemovedThenRebuildFails()
      throws IOException, InterruptedException {
    whenAppBuckFileRemovedThenRebuildFails(WatchmanWatcher.CursorType.NAMED.toString());
  }

  @Test
  public void withClockIdCursorAppBuckFileRemovedThenRebuildFails()
      throws IOException, InterruptedException {
    whenAppBuckFileRemovedThenRebuildFails(WatchmanWatcher.CursorType.CLOCK_ID.toString());
  }

  private void whenActivityBuckFileRemovedThenRebuildFails(String cursorType)
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();
    TestDataHelper.overrideBuckconfig(
        workspace,
        ImmutableMap.of("project", ImmutableMap.of("watchman_cursor", cursorType)));

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertSuccess();

    String fileName = "java/com/example/activity/BUCK";
    Files.delete(workspace.getPath(fileName));

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertFailure();
  }

  @Test
  public void withNamedCursorActivityBuckFileRemovedThenRebuildFails()
      throws IOException, InterruptedException {
    whenActivityBuckFileRemovedThenRebuildFails(WatchmanWatcher.CursorType.NAMED.toString());
  }

  @Test
  public void withClockIdCursorActivityBuckFileRemovedThenRebuildFails()
      throws IOException, InterruptedException {
    whenActivityBuckFileRemovedThenRebuildFails(WatchmanWatcher.CursorType.CLOCK_ID.toString());
  }

  private void whenSourceInputRemovedThenRebuildFails(String cursorType)
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();
    TestDataHelper.overrideBuckconfig(
        workspace,
        ImmutableMap.of("project", ImmutableMap.of("watchman_cursor", cursorType)));

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertSuccess();

    String fileName = "java/com/example/activity/MyFirstActivity.java";
    Files.delete(workspace.getPath(fileName));

    try {
      workspace.runBuckdCommand("build", "//java/com/example/activity:activity");
      fail("Should have thrown HumanReadableException.");
    } catch (java.lang.RuntimeException e) {
      assertThat("Failure should have been due to file removal.", e.getMessage(),
          containsString("MyFirstActivity.java"));
    }
  }

  @Test
  public void withNamedCursorSourceInputRemovedThenRebuildFails()
      throws IOException, InterruptedException {
    whenSourceInputRemovedThenRebuildFails(WatchmanWatcher.CursorType.NAMED.toString());
  }

  @Test
  public void withClockIdCursorSourceInputRemovedThenRebuildFails()
      throws IOException, InterruptedException {
    whenSourceInputRemovedThenRebuildFails(WatchmanWatcher.CursorType.CLOCK_ID.toString());
  }

  private void whenSourceInputInvalidatedThenRebuildFails(String cursorType)
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();
    TestDataHelper.overrideBuckconfig(
        workspace,
        ImmutableMap.of("project", ImmutableMap.of("watchman_cursor", cursorType)));

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertSuccess();

    String fileName = "java/com/example/activity/MyFirstActivity.java";
    Files.delete(workspace.getPath(fileName));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(containsString("MyFirstActivity.java"));

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity");
  }

  @Test
  public void withNamedCursorSourceInputInvalidatedThenRebuildFails()
      throws IOException, InterruptedException {
    whenSourceInputInvalidatedThenRebuildFails(WatchmanWatcher.CursorType.NAMED.toString());
  }

  @Test
  public void withClockIdCursorSourceInputInvalidatedThenRebuildFails()
      throws IOException, InterruptedException {
    whenSourceInputInvalidatedThenRebuildFails(WatchmanWatcher.CursorType.CLOCK_ID.toString());
  }

  private void whenAppBuckFileInvalidatedThenRebuildFails(String cursorType)
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();
    TestDataHelper.overrideBuckconfig(
        workspace,
        ImmutableMap.of("project", ImmutableMap.of("watchman_cursor", cursorType)));

    workspace.runBuckdCommand("build", "app").assertSuccess();

    String fileName = "apps/myapp/BUCK";
    Files.write(workspace.getPath(fileName), "Some Illegal Python".getBytes(Charsets.US_ASCII));

    ProcessResult result = workspace.runBuckdCommand("build", "app");
    assertThat(
        "Failure should be due to syntax error.",
        result.getStderr(),
        containsString("Syntax error"));
    result.assertFailure();
  }

  @Test
  public void withNamedCursorAppBuckFileInvalidatedThenRebuildFails()
      throws IOException, InterruptedException {
    whenAppBuckFileInvalidatedThenRebuildFails(WatchmanWatcher.CursorType.NAMED.toString());
  }

  @Test
  public void withClockIdCursorAppBuckFileInvalidatedThenRebuildFails()
      throws IOException, InterruptedException {
    whenAppBuckFileInvalidatedThenRebuildFails(WatchmanWatcher.CursorType.CLOCK_ID.toString());
  }

  private void whenCrossCellSourceInvalidatedThenRebuildFails(String cursorType)
      throws IOException, InterruptedException {
    final ProjectWorkspace primary = TestDataHelper.createProjectWorkspaceForScenario(
        this, "xplat_file_watching/primary", tmp.newFolder());
    primary.setUp();
    final ProjectWorkspace secondary = TestDataHelper.createProjectWorkspaceForScenario(
        this, "xplat_file_watching/secondary", tmp.newFolder());
    secondary.setUp();
    TestDataHelper.overrideBuckconfig(
        primary,
        ImmutableMap.of(
            "repositories", ImmutableMap.of(
                "secondary",
                secondary.getPath(".").normalize().toString()),
            "project", ImmutableMap.of(
                "watch_cells", "true",
                "watchman_cursor", cursorType)));
    TestDataHelper.overrideBuckconfig(
        secondary,
        ImmutableMap.of("project", ImmutableMap.of("watchman_cursor", cursorType)));

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
  public void withNamedCursorCrossCellSourceInvalidatedThenRebuildFails()
      throws IOException, InterruptedException {
    whenCrossCellSourceInvalidatedThenRebuildFails(WatchmanWatcher.CursorType.NAMED.toString());
  }

  @Test
  public void withClockIdCursorCrossCellSourceInvalidatedThenRebuildFails()
      throws IOException, InterruptedException {
    whenCrossCellSourceInvalidatedThenRebuildFails(WatchmanWatcher.CursorType.CLOCK_ID.toString());
  }

  @Test
  public void whenBuckConfigChangesParserInvalidated()
      throws IOException, InterruptedException {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());

    Object daemon = Main.getDaemon(
        new TestCellBuilder().setBuckConfig(
            FakeBuckConfig.builder().setSections(
                ImmutableMap.of("somesection", ImmutableMap.of("somename", "somevalue"))).build())
            .setFilesystem(filesystem)
            .build(),
        ObjectMappers.newDefaultInstance());

    assertEquals(
        "Daemon should not be replaced when config equal.", daemon,
        Main.getDaemon(
            new TestCellBuilder().setBuckConfig(
                FakeBuckConfig.builder()
                    .setSections(
                        ImmutableMap.of("somesection", ImmutableMap.of("somename", "somevalue")))
                    .build())
                .setFilesystem(filesystem)
                .build(),
            ObjectMappers.newDefaultInstance()));

    assertNotEquals(
        "Daemon should be replaced when config not equal.", daemon,
        Main.getDaemon(
            new TestCellBuilder().setBuckConfig(
                FakeBuckConfig.builder().setSections(
                    ImmutableMap.of(
                        "somesection",
                        ImmutableMap.of("somename", "someothervalue"))).build())
                .setFilesystem(filesystem)
                .build(),
            ObjectMappers.newDefaultInstance()));
  }

  @Test
  public void whenBuckBuiltTwiceLogIsPresent()
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertSuccess();

    Path buildLogFile = workspace.getPath("buck-out/bin/build.log");

    assertTrue(Files.isRegularFile(buildLogFile));
    Files.delete(buildLogFile);

    ProcessResult rebuild =
        workspace.runBuckdCommand("build", "//java/com/example/activity:activity");
    rebuild.assertSuccess();

    buildLogFile = workspace.getPath("buck-out/bin/build.log");
    assertTrue(Files.isRegularFile(buildLogFile));
  }

  @Test
  public void whenAndroidNdkVersionChangesParserInvalidated()
      throws IOException, InterruptedException {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());

    BuckConfig buckConfig1 = FakeBuckConfig.builder()
        .setSections(ImmutableMap.of(
            "ndk",
            ImmutableMap.of("ndk_version", "something")))
        .build();

    BuckConfig buckConfig2 = FakeBuckConfig.builder()
        .setSections(ImmutableMap.of(
            "ndk",
            ImmutableMap.of("ndk_version", "different")))
        .build();

    Object daemon = Main.getDaemon(
        new TestCellBuilder()
            .setBuckConfig(buckConfig1)
            .setFilesystem(filesystem)
            .build(),
        ObjectMappers.newDefaultInstance());

    assertNotEquals(
        "Daemon should be replaced when not equal.", daemon,
        Main.getDaemon(
            new TestCellBuilder()
                .setBuckConfig(buckConfig2)
                .setFilesystem(filesystem)
                .build(),
            ObjectMappers.newDefaultInstance()));
  }
}
