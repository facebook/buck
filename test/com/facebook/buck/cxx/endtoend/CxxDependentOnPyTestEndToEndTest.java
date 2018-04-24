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

package com.facebook.buck.cxx.endtoend;

import static java.lang.Thread.sleep;

import com.facebook.buck.testutil.endtoend.ConfigSetBuilder;
import com.facebook.buck.testutil.endtoend.EndToEndEnvironment;
import com.facebook.buck.testutil.endtoend.EndToEndRunner;
import com.facebook.buck.testutil.endtoend.EndToEndTestDescriptor;
import com.facebook.buck.testutil.endtoend.EndToEndWorkspace;
import com.facebook.buck.testutil.endtoend.Environment;
import com.facebook.buck.testutil.endtoend.EnvironmentFor;
import com.facebook.buck.testutil.endtoend.ToggleState;
import com.facebook.buck.util.ProcessExecutor.LaunchedProcess;
import com.facebook.buck.util.environment.Platform;
import java.nio.file.NoSuchFileException;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(EndToEndRunner.class)
public class CxxDependentOnPyTestEndToEndTest {

  @Environment
  public static EndToEndEnvironment getBaseEnvironment() {
    ConfigSetBuilder configSetBuilder = new ConfigSetBuilder();
    return new EndToEndEnvironment()
        .withBuckdToggled(ToggleState.ON)
        .addTemplates("cxx_dependent_on_py", "test_runners")
        .withCommand("test")
        .withTargets("//py_lib:test")
        .addLocalConfigSet(configSetBuilder.addNeverendingTestrunner().build());
  }

  @EnvironmentFor(testNames = {"interruptTestsGracefully"})
  public static EndToEndEnvironment interruptEnvironment() {
    return getBaseEnvironment();
  }

  @Before
  public void assumePlatform() {
    // signal handling is far different on windows
    Assume.assumeTrue(Platform.detect() == Platform.LINUX || Platform.detect() == Platform.MACOS);
  }

  private void assertFileExists(EndToEndWorkspace workspace, String filename, int timeout)
      throws Exception {
    boolean foundFile = false;
    long startTime = System.currentTimeMillis();
    long timeoutMillis = timeout * 1000;
    while (!foundFile && System.currentTimeMillis() < startTime + timeoutMillis) {
      try {
        workspace.getFileContents(filename);
        foundFile = true;
      } catch (NoSuchFileException e) {
        sleep(Math.min(1000, Math.max(0, startTime + timeoutMillis - System.currentTimeMillis())));
      }
    }
    Assert.assertTrue(
        String.format("Can't find %s after %d seconds", filename, timeout), foundFile);
  }

  @Test
  public void interruptTestsGracefully(EndToEndTestDescriptor test, EndToEndWorkspace workspace)
      throws Exception {
    LaunchedProcess launchedProcess = workspace.launchBuckCommandProcess(test);

    assertFileExists(workspace, "started_neverending_test_runner.txt", 25);

    workspace.sendSigInt(launchedProcess);
    Assert.assertTrue(workspace.waitForProcess(launchedProcess));
    assertFileExists(workspace, "signal_cancelled_runner.txt", 5);
  }
}
