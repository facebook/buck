/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.jvm.java.JacocoConstants;
import com.facebook.buck.jvm.java.runner.FileClassPathRunner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.immutables.value.Value;

@BuckStyleValueWithBuilder
abstract class AndroidInstrumentationTestJVMArgs {

  private static final String INSTRUMENTATION_TEST_RUNNER =
      "com.facebook.buck.testrunner.InstrumentationMain";

  abstract String getPathToAdbExecutable();

  abstract Optional<Path> getDirectoryForTestResults();

  abstract String getTestPackage();

  abstract String getTestRunner();

  abstract String getTargetPackage();

  abstract String getDdmlibJarPath();

  abstract String getKxmlJarPath();

  abstract String getGuavaJarPath();

  /**
   * @return If true, suspend the JVM to allow a debugger to attach after launch. Defaults to false.
   */
  @Value.Default
  boolean isDebugEnabled() {
    return false;
  }

  /** @return If true, code coverage is enabled */
  @Value.Default
  boolean isCodeCoverageEnabled() {
    return false;
  }

  abstract String getAndroidToolsCommonJarPath();

  abstract Optional<String> getDeviceSerial();

  abstract Optional<Path> getInstrumentationApkPath();

  abstract Optional<Path> getApkUnderTestPath();

  abstract Optional<Path> getApkUnderTestExopackageLocalDir();

  abstract Optional<String> getTestFilter();

  abstract Optional<Path> getExopackageLocalDir();

  /** @return The filesystem path to the compiled Buck test runner classes. */
  abstract Path getTestRunnerClasspath();

  /** @return The version of Java used for the test runner. */
  abstract OptionalInt getTestRunnerJavaVersion();

  abstract ImmutableMap<String, String> getEnvironmentOverrides();

  public void formatCommandLineArgsToList(
      ProjectFilesystem filesystem,
      ImmutableList.Builder<String> args,
      Supplier<Path> classpathArgfile) {
    if (!shouldUseClasspathArgfile()) {
      // NOTE(agallagher): These propbably don't belong here, but buck integration tests need
      // to find the test runner classes, so propagate these down via the relevant properties.
      args.add(
          String.format(
              "-D%s=%s",
              FileClassPathRunner.TESTRUNNER_CLASSES_PROPERTY, getTestRunnerClasspath()));
    }

    if (getDeviceSerial().isPresent()) {
      args.add(String.format("-Dbuck.device.id=%s", getDeviceSerial().get()));
    }

    if (shouldUseClasspathArgfile()) {
      // Java 9+ supports argfiles for commandline arguments. We leverage this when we know we're
      // launching a version of Java that supports this, as classloader changes in Java 9 preclude
      // use from using the approach we use for Java 8-.
      args.add("@" + filesystem.resolve(classpathArgfile.get()));
    } else {
      args.add("-classpath", getClasspathContents(filesystem));
    }

    args.add(FileClassPathRunner.class.getName());
    args.add(INSTRUMENTATION_TEST_RUNNER);

    // The first argument to the test runner is where the test results should be written. It is not
    // reliable to write test results to stdout or stderr because there may be output from the unit
    // tests written to those file descriptors, as well.
    if (getDirectoryForTestResults().isPresent()) {
      args.add("--output", getDirectoryForTestResults().get().toString());
    }

    // The InstrumentationRunner requires the package name of the test
    args.add("--test-package-name", getTestPackage());
    args.add("--target-package-name", getTargetPackage());
    args.add("--test-runner", getTestRunner());
    args.add("--adb-executable-path", getPathToAdbExecutable());

    if (getTestFilter().isPresent()) {
      args.add("--extra-instrumentation-argument", "class=" + getTestFilter().get());
    }

    for (Map.Entry<String, String> argPair : getEnvironmentOverrides().entrySet()) {
      args.add(
          "--extra-instrumentation-argument",
          String.format(Locale.US, "%s=%s", argPair.getKey(), argPair.getValue()));
    }

    // If the test APK supports exopackage installation, this will be the location of a
    // folder which contains the contents of the expackage dir exactly how they should be
    // laid out on the device along with a metadata file which contains the path to the root.
    // This way, the instrumentation test runner can simply `adb push` the requisite files to
    // the device.
    if (getExopackageLocalDir().isPresent()) {
      args.add("--exopackage-local-dir", getExopackageLocalDir().get().toString());
    }

    if (getApkUnderTestPath().isPresent()) {
      args.add("--apk-under-test-path", getApkUnderTestPath().get().toFile().getAbsolutePath());
    }

    // We currently don't support exo for apk-under-test, but when it is supported, this will
    // be populated with a path to a folder containing the exopackage layout and metadata for
    // the apk under test.
    if (getApkUnderTestExopackageLocalDir().isPresent()) {
      args.add(
          "--apk-under-test-exopackage-local-dir",
          getApkUnderTestExopackageLocalDir().get().toString());
    }

    if (getInstrumentationApkPath().isPresent()) {
      args.add(
          "--instrumentation-apk-path",
          getInstrumentationApkPath().get().toFile().getAbsolutePath());
    }

    // If enabled, the runner should enable debugging so that the test pauses and waits for
    // a debugger to attach.
    if (isDebugEnabled()) {
      args.add("--debug");
    }

    if (isCodeCoverageEnabled()) {
      args.add("--code-coverage");
      String codeCoverageOutputFile =
          String.format(
              "%s/%s",
              JacocoConstants.getJacocoOutputDir(filesystem),
              JacocoConstants.JACOCO_EXEC_COVERAGE_FILE);
      args.add("--code-coverage-output-file", codeCoverageOutputFile);
    }
  }

  /** Whether or not we should use an argfile for the classpath when invoking Java. */
  public boolean shouldUseClasspathArgfile() {
    return getTestRunnerJavaVersion().isPresent() && getTestRunnerJavaVersion().getAsInt() >= 9;
  }

  /** Writes an argfile for the classpath to a file, which is supported in Java 9+. */
  public void writeClasspathArgfile(ProjectFilesystem filesystem, Path argfile) throws IOException {
    StringBuilder builder = new StringBuilder();
    builder.append("-classpath");
    builder.append(System.lineSeparator());
    builder.append('"');
    builder.append(escapePathForArgfile(getClasspathContents(filesystem)));
    builder.append('"');
    builder.append(System.lineSeparator());

    filesystem.writeContentsToPath(builder.toString(), argfile);
  }

  private String escapePathForArgfile(String path) {
    return path.replace("\\", "\\\\");
  }

  private String getClasspathContents(ProjectFilesystem filesystem) {
    AbsPath rootPath = filesystem.getRootPath();
    return Stream.of(
            getTestRunnerClasspath(),
            Paths.get(getDdmlibJarPath()),
            Paths.get(getKxmlJarPath()),
            Paths.get(getGuavaJarPath()),
            Paths.get(getAndroidToolsCommonJarPath()))
        .map(p -> ProjectFilesystemUtils.relativize(rootPath, p))
        .map(Objects::toString)
        .collect(Collectors.joining(File.pathSeparator));
  }
}
