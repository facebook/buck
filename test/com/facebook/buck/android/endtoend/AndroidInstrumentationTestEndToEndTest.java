/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.android.endtoend;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.endtoend.ConfigSetBuilder;
import com.facebook.buck.testutil.endtoend.EndToEndEnvironment;
import com.facebook.buck.testutil.endtoend.EndToEndRunner;
import com.facebook.buck.testutil.endtoend.EndToEndTestDescriptor;
import com.facebook.buck.testutil.endtoend.EndToEndWorkspace;
import com.facebook.buck.testutil.endtoend.Environment;
import com.facebook.buck.util.BgProcessKiller;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * E2E tests for running buck instrumentation tests on an emulator, specifically designed for
 * testing interactions with an external testrunner
 */
@RunWith(EndToEndRunner.class)
public class AndroidInstrumentationTestEndToEndTest {
  private static final Logger LOG = Logger.get(AndroidInstrumentationTestEndToEndTest.class);

  @Before
  public void assumeEnvironment() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();

    // This test requires a real device or an emulator
    Assume.assumeTrue(
        "Skipping test because there is no android device available", hasExactlyOneRealDevice());
  }

  private static boolean hasExactlyOneRealDevice() {
    Path adbPath =
        Paths.get(EnvVariablesProvider.getSystemEnv().get("ANDROID_SDK"))
            .resolve(Paths.get("platform-tools", "adb"));
    if (!adbPath.toFile().isFile()) {
      return false;
    }
    try {
      ProcessBuilder builder = new ProcessBuilder();
      builder.command(adbPath.toString(), "devices");
      Process process = BgProcessKiller.startProcess(builder);
      BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
      // Adb output looks like:
      // List of devices attached
      // device1
      // device2...
      List<String> devices =
          br.lines().skip(1).filter(s -> !s.trim().isEmpty()).collect(Collectors.toList());
      return process.waitFor() == 0 && devices.size() == 1;
    } catch (IOException | InterruptedException e) {
      // Was unable to detect devices
      LOG.warn(e, "Wasn't able to detect a device");
      return false;
    }
  }

  @Environment
  public static EndToEndEnvironment baseEnvironment() {
    ConfigSetBuilder configSetBuilder = new ConfigSetBuilder();
    return new EndToEndEnvironment()
        .addTemplates("mobile")
        .withCommand("test")
        .withTargets(
            "//android/instrumentation_tests/com/facebook/buck/demo:test_and_app_together-exo")
        .addLocalConfigSet(configSetBuilder.build())
        .addLocalConfigSet(configSetBuilder.addExternalTestRunner().build());
  }

  /** Run */
  @Test
  public void test(EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {
    ProcessResult result = workspace.runBuckCommand(test);
    result.assertSuccess("Did not successfully build");
  }
}
