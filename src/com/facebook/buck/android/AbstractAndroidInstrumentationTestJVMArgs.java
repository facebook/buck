/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.jvm.java.runner.FileClassPathRunner;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractAndroidInstrumentationTestJVMArgs {

  private static final String INSTRUMENTATION_TEST_RUNNER =
      "com.facebook.buck.testrunner.InstrumentationMain";

  abstract String getPathToAdbExecutable();

  abstract Optional<Path> getDirectoryForTestResults();

  abstract String getTestPackage();

  abstract String getTestRunner();

  abstract String getDdmlibJarPath();

  abstract String getKxmlJarPath();

  abstract String getGuavaJarPath();

  abstract String getAndroidToolsCommonJarPath();

  abstract Optional<String> getDeviceSerial();

  abstract Optional<Path> getInstrumentationApkPath();

  abstract Optional<Path> getApkUnderTestPath();

  abstract Optional<String> getTestFilter();

  /** @return The filesystem path to the compiled Buck test runner classes. */
  abstract Path getTestRunnerClasspath();

  public void formatCommandLineArgsToList(ImmutableList.Builder<String> args) {
    // NOTE(agallagher): These propbably don't belong here, but buck integration tests need
    // to find the test runner classes, so propagate these down via the relevant properties.
    args.add(String.format("-Dbuck.testrunner_classes=%s", getTestRunnerClasspath()));

    if (getDeviceSerial().isPresent()) {
      args.add(String.format("-Dbuck.device.id=%s", getDeviceSerial().get()));
    }

    args.add(
        "-classpath",
        getTestRunnerClasspath().toString()
            + File.pathSeparator
            + this.getDdmlibJarPath()
            + File.pathSeparator
            + this.getKxmlJarPath()
            + File.pathSeparator
            + this.getGuavaJarPath()
            + File.pathSeparator
            + this.getAndroidToolsCommonJarPath());
    args.add(FileClassPathRunner.class.getName());
    args.add(INSTRUMENTATION_TEST_RUNNER);

    // The first argument to the test runner is where the test results should be written. It is not
    // reliable to write test results to stdout or stderr because there may be output from the unit
    // tests written to those file descriptors, as well.
    if (getDirectoryForTestResults().isPresent()) {
      args.add("--output", getDirectoryForTestResults().get().toString());
    }

    args.add("--test-package-name", getTestPackage());
    args.add("--test-runner", getTestRunner());
    args.add("--adb-executable-path", getPathToAdbExecutable());

    if (getTestFilter().isPresent()) {
      args.add("--extra-instrumentation-argument", "class=" + getTestFilter().get());
    }

    if (getApkUnderTestPath().isPresent()) {
      args.add("--apk-under-test-path", getApkUnderTestPath().get().toFile().getAbsolutePath());
    }
    if (getInstrumentationApkPath().isPresent()) {
      args.add(
          "--instrumentation-apk-path",
          getInstrumentationApkPath().get().toFile().getAbsolutePath());
    }
  }
}
