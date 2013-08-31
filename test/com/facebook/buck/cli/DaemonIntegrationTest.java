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
import static org.junit.Assert.fail;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.CapturingPrintStream;
import com.google.common.base.Throwables;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DaemonIntegrationTest {

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
  }

  /**
   * This verifies that when the user tries to run the Buck Main method, while it is already running,
   * the second call will fail to avoid multiple threads accessing and corrupting the static state
   * used by the Buck daemon.
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
          int exitCode = main.tryRunMainWithExitCode(tmp.getRoot(), "build", "//:sleep");
          assertEquals("Should return 0 when no command running.", 0, exitCode);
        } catch (Exception e) {
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
          int exitCode = main.tryRunMainWithExitCode(tmp.getRoot(), "targets");
          assertEquals("Should return 1 when command running.", Main.BUSY_EXIT_CODE, exitCode);
        } catch (Exception e) {
          fail("Should not throw IOException.");
          throw Throwables.propagate(e);
        }
      }
    }, 500L, TimeUnit.MILLISECONDS);
    firstThread.get();
    secondThread.get();
  }
}
