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

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.TestRepositoryBuilder;
import com.facebook.buck.rules.TestRunEvent;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.DelegatingInputStream;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.timing.FakeClock;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.Subscribe;
import com.martiansoftware.nailgun.NGClientListener;
import com.martiansoftware.nailgun.NGContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

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
    Watchman watchman = Watchman.build(
        tmp.getRootPath(), getWatchmanEnv(), new TestConsole(), new FakeClock(0));

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
      final Thread commandThread = Thread.currentThread();
      context.addClientListener(
          new NGClientListener() {
            @Override
            public void clientDisconnected() throws InterruptedException {
              commandThread.interrupt();
            }
          });
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
    return new Runnable() {
      @Override
      public void run() {
        try {
          Main main = new Main(new CapturingPrintStream(), new CapturingPrintStream());
          int exitCode = main.tryRunMainWithExitCode(
              new BuildId(),
              tmp.getRootPath(),
              Optional.<NGContext>of(new TestContext()),
              ImmutableMap.copyOf(System.getenv()),
              args);
          assertEquals("Unexpected exit code.", expectedExitCode, exitCode);
        } catch (IOException e) {
          fail("Should not throw exception.");
          throw Throwables.propagate(e);
        } catch (InterruptedException e) {
          fail("Should not throw exception.");
          Thread.currentThread().interrupt();
        }
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
      BuckEventListener listener = new BuckEventListener() {
        @Subscribe
        @SuppressWarnings("unused")
        public void onEvent(TestRunEvent.Started event) {
          // When tests start running, make the heartbeat stream time out.
          inputStream.setDelegate(TestContext.createHeartBeatStream(2 * timeoutMillis));
        }

        @Override
        public void outputTrace(BuildId buildId) throws InterruptedException {
          // do nothing
        }
      };
      ProcessResult result = workspace.runBuckdCommand(context, listener, "test", "//:test");
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
      BuckEventListener listener = new BuckEventListener() {
        @Subscribe
        @SuppressWarnings("unused")
        public void onEvent(TestRunEvent.Started event) {
          // When tests start running, make the heartbeat stream simulate a disconnection.
          inputStream.setDelegate(TestContext.createDisconnectionStream(disconnectMillis));
        }

        @Override
        public void outputTrace(BuildId buildId) throws InterruptedException {
          // do nothing
        }
      };
      ProcessResult result = workspace.runBuckdCommand(context, listener, "test", "//:test");
      result.assertFailure();
      assertThat(result.getStderr(), containsString("InterruptedException"));
    }
  }

  @Test
  public void whenAppBuckFileRemovedThenRebuildFails()
      throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();

    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckdCommand("build", "app");
    result.assertSuccess();

    String fileName = "apps/myapp/BUCK";
    Files.delete(workspace.getPath(fileName));

    workspace.runBuckdCommand("build", "app").assertFailure();
  }

  @Test
  public void whenActivityBuckFileRemovedThenRebuildFails()
      throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertSuccess();

    String fileName = "java/com/example/activity/BUCK";
    Files.delete(workspace.getPath(fileName));

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertFailure();
  }

  @Test
  public void whenSourceInputRemovedThenRebuildFails()
      throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

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
  public void whenSourceInputInvalidatedThenRebuildFails()
      throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertSuccess();

    String fileName = "java/com/example/activity/MyFirstActivity.java";
    Files.delete(workspace.getPath(fileName));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(containsString("MyFirstActivity.java"));

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity");
  }

  @Test
  public void whenAppBuckFileInvalidatedThenRebuildFails()
      throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

    workspace.runBuckdCommand("build", "app").assertSuccess();

    String fileName = "apps/myapp/BUCK";
    Files.write(workspace.getPath(fileName), "Some Illegal Python".getBytes(Charsets.US_ASCII));

    ProcessResult result = workspace.runBuckdCommand("build", "app");
    assertThat(
        "Failure should be due to syntax error.",
        result.getStderr(),
        containsString("SyntaxError: invalid syntax"));
    result.assertFailure();
  }

  @Test
  public void whenBuckConfigChangesParserInvalidated()
      throws IOException, InterruptedException {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot().toPath());

    Object daemon = Main.getDaemon(
        new TestRepositoryBuilder().setBuckConfig(
            new FakeBuckConfig(
                ImmutableMap.of("somesection", ImmutableMap.of("somename", "somevalue"))))
            .setFilesystem(filesystem)
            .build(),
        ParserConfig.GlobHandler.PYTHON);
    assertEquals(
        "Daemon should not be replaced when config equal.", daemon,
        Main.getDaemon(
            new TestRepositoryBuilder().setBuckConfig(
                new FakeBuckConfig(
                    ImmutableMap.of("somesection", ImmutableMap.of("somename", "somevalue"))))
                .setFilesystem(filesystem)
                .build(),
            ParserConfig.GlobHandler.PYTHON));

    assertNotEquals(
        "Daemon should be replaced when config not equal.", daemon,
        Main.getDaemon(
            new TestRepositoryBuilder().setBuckConfig(
                new FakeBuckConfig(
                    ImmutableMap.of(
                        "somesection",
                        ImmutableMap.of("somename", "someothervalue"))))
                .setFilesystem(filesystem)
                .build(),
            ParserConfig.GlobHandler.PYTHON));
  }

  @Test
  public void whenBuckBuiltTwiceLogIsPresent()
      throws IOException, InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
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
  public void whenAndroidDirectoryResolverChangesParserInvalidated()
      throws IOException, InterruptedException {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot().toPath());

    Object daemon = Main.getDaemon(
        new TestRepositoryBuilder()
            .setAndroidDirectoryResolver(
                new FakeAndroidDirectoryResolver(
                    Optional.<Path>absent(),
                    Optional.<Path>absent(),
                    Optional.of("something")))
            .setFilesystem(filesystem)
            .build(),
        ParserConfig.GlobHandler.PYTHON);

    assertNotEquals(
        "Daemon should be replaced when not equal.", daemon,
        Main.getDaemon(
            new TestRepositoryBuilder()
                .setAndroidDirectoryResolver(
                    new FakeAndroidDirectoryResolver(
                        Optional.<Path>absent(),
                        Optional.<Path>absent(),
                        Optional.of("different")))
                .setFilesystem(filesystem)
                .build(),
            ParserConfig.GlobHandler.PYTHON));
  }
}
