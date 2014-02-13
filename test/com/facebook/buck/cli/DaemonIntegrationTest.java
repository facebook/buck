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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.eventbus.Subscribe;
import com.google.common.io.Files;
import com.martiansoftware.nailgun.NGClientListener;
import com.martiansoftware.nailgun.NGConstants;
import com.martiansoftware.nailgun.NGContext;
import com.martiansoftware.nailgun.NGExitException;
import com.martiansoftware.nailgun.NGInputStream;
import com.martiansoftware.nailgun.NGSecurityManager;

import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.util.Arrays;
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

  @Before
  public void setUp() {
    executorService = Executors.newScheduledThreadPool(2);
  }

  @After
  public void tearDown() {
    executorService.shutdown();
    Main.resetDaemon();
  }

  /**
   * This verifies that when the user tries to run the Buck Main method, while it is already
   * running, the second call will fail. Serializing command execution in this way avoids
   * multiple threads accessing and corrupting the static state used by the Buck daemon.
   */
  @Test
  public void testExclusiveExecution()
      throws IOException, InterruptedException, ExecutionException {
    final CapturingPrintStream stdOut = new CapturingPrintStream();
    final CapturingPrintStream firstThreadStdErr = new CapturingPrintStream();
    final CapturingPrintStream secondThreadStdErr = new CapturingPrintStream();

    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "exclusive_execution", tmp);
    workspace.setUp();

    Future<?> firstThread = executorService.schedule(new Runnable() {
      @Override
      public void run() {
        try {
          Main main = new Main(stdOut, firstThreadStdErr);
          int exitCode = main.tryRunMainWithExitCode(tmp.getRoot(),
              Optional.<NGContext>absent(),
              "build",
              "//:sleep");
          assertEquals("Should return 0 when no command running.", SUCCESS_EXIT_CODE, exitCode);
        } catch (IOException e) {
          fail("Should not throw IOException");
          throw Throwables.propagate(e);
        }
      }
    }, 0, TimeUnit.MILLISECONDS);
    Future<?> secondThread = executorService.schedule(new Runnable() {
      @Override
      public void run() {
        try {
          Main main = new Main(stdOut, secondThreadStdErr);
          int exitCode = main.tryRunMainWithExitCode(tmp.getRoot(),
              Optional.<NGContext>absent(),
              "targets");
          assertEquals("Should return 2 when command running.", Main.BUSY_EXIT_CODE, exitCode);
        } catch (IOException e) {
          fail("Should not throw IOException.");
          throw Throwables.propagate(e);
        }
      }
    }, 500L, TimeUnit.MILLISECONDS);
    firstThread.get();
    secondThread.get();
  }

  private InputStream createHeartbeatStream(int count) {
    final int BYTES_PER_HEARTBEAT = 5;
    byte[] bytes = new byte[BYTES_PER_HEARTBEAT * count];
    Arrays.fill(bytes, NGConstants.CHUNKTYPE_HEARTBEAT);
    return new ByteArrayInputStream(bytes);
  }

  /**
   * This verifies that a client disconnection will be detected by a Nailgun
   * NGInputStream which then calls a clientDisconnected handler which interrupts Buck command
   * processing.
   */
  @Test
  public void whenClientDisconnectsThenCommandIsInterrupted()
      throws InterruptedException, IOException {

    // NGInputStream test double which provides access to registered client listener.
    class TestNGInputStream extends NGInputStream {

      public NGClientListener listener = null;

      public TestNGInputStream(InputStream in, DataOutputStream out, PrintStream serverLog) {
        super(in, out, serverLog, 10000 /* client timeout millis */);
      }

      @Override
      public synchronized void addClientListener(NGClientListener listener) {
        this.listener = listener;
      }
    }

    // Build an NGContext connected to an NGInputStream reading from a stream of heartbeats.
    Thread.currentThread().setName("Test");
    CapturingPrintStream serverLog = new CapturingPrintStream();
    NGContext context = new NGContext();
    try (TestNGInputStream inputStream = new TestNGInputStream(
            new DataInputStream(createHeartbeatStream(100)),
            new DataOutputStream(new ByteArrayOutputStream(0)),
            serverLog)) {
      context.setArgs(new String[] {"targets"});
      context.in = inputStream;
      context.out = new CapturingPrintStream();
      context.err = new CapturingPrintStream();
      context.setExitStream(new CapturingPrintStream());

      // NGSecurityManager is used to convert System.exit() calls in to NGExitExceptions.
      SecurityManager originalSecurityManager = System.getSecurityManager();

      // Run command to register client listener.
      try {
        System.setSecurityManager(new NGSecurityManager(originalSecurityManager));
        Main.nailMain(context);
        fail("Should throw NGExitException.");
      } catch (NGExitException e) {
        assertEquals("Should exit with status 0.", SUCCESS_EXIT_CODE, e.getStatus());
      } finally {
        System.setSecurityManager(originalSecurityManager);
      }

      // Check listener was registered calls System.exit() with client disconnect exit code.
      try {
        System.setSecurityManager(new NGSecurityManager(originalSecurityManager));
        assertNotNull("Should register client listener.", inputStream.listener);
        inputStream.listener.clientDisconnected();
        fail("Should throw InterruptedException.");
      } catch (InterruptedException e) {
        assertEquals("Should be client disconnection.", "Client disconnected.", e.getMessage());
      } finally {
        System.setSecurityManager(originalSecurityManager);
      }
    }
  }

  @Test
  public void whenAppBuckFileRemovedThenRebuildFails()
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckdCommand("build", "app");
    result.assertExitCode(0);

    String fileName = "apps/myapp/BUCK";
    assertTrue("Should delete BUCK file successfully", workspace.getFile(fileName).delete());
    waitForChange(Paths.get(fileName));

    try {
      workspace.runBuckdCommand("build", "app");
      fail("Should have thrown HumanReadableException.");
    } catch (HumanReadableException e) {
      assertThat("Failure should have been due to BUCK file removal.", e.getMessage(),
          Matchers.containsString(fileName));
    }
  }

  @Test
  public void whenActivityBuckFileRemovedThenRebuildFails()
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertExitCode(0);

    String fileName = "java/com/example/activity/BUCK";
    assertTrue("Should delete BUCK file successfully.", workspace.getFile(fileName).delete());
    waitForChange(Paths.get(fileName));

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertExitCode(
        Main.FAIL_EXIT_CODE);
  }

  @Test
  public void whenSourceInputRemovedThenRebuildFails()
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertExitCode(0);

    String fileName = "java/com/example/activity/MyFirstActivity.java";
    assertTrue("Should delete BUCK file successfully.", workspace.getFile(fileName).delete());
    waitForChange(Paths.get(fileName));

    try {
      workspace.runBuckdCommand("build", "//java/com/example/activity:activity");
      fail("Should have thrown HumanReadableException.");
    } catch (java.lang.RuntimeException e) {
      assertThat("Failure should have been due to file removal.", e.getMessage(),
          Matchers.containsString(fileName));
    }
  }

  @Test
  public void whenSourceInputInvalidatedThenRebuildFails() throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "file_watching", tmp);
    workspace.setUp();

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertExitCode(0);

    String fileName = "java/com/example/activity/MyFirstActivity.java";
    Files.write("Some Illegal Java".getBytes(Charsets.US_ASCII), workspace.getFile(fileName));
    waitForChange(Paths.get(fileName));

    workspace.runBuckdCommand("build", "//java/com/example/activity:activity").assertExitCode(
        Main.FAIL_EXIT_CODE);
  }

  private void waitForChange(final Path path) throws IOException {

    class Watcher {
      private Path path;
      private boolean watchedChange = false;

      public Watcher(Path path) {
        this.path = path;
        watchedChange = false;
      }

      public boolean watchedChange() {
        return watchedChange;
      }

      @Subscribe
      public synchronized void onEvent(WatchEvent<?> event) throws IOException {
        if (path.equals(event.context())) {
          watchedChange = true;
        }
      }
    }

    Watcher watcher = new Watcher(path);
    Main.registerFileWatcher(watcher);
    while (!watcher.watchedChange()) {
      Main.watchFilesystem();
    }
  }
}
