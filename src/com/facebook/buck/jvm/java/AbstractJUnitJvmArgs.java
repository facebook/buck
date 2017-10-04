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

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.runner.FileClassPathRunner;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;
import org.immutables.value.Value;

/**
 * Holds and formats the arguments and system properties that should be passed to the JVM to run
 * JUnit.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractJUnitJvmArgs {
  private static final String BUILD_ID_PROPERTY = "com.facebook.buck.buildId";
  private static final String MODULE_BASE_PATH_PROPERTY = "com.facebook.buck.moduleBasePath";
  private static final String STD_OUT_LOG_LEVEL_PROPERTY = "com.facebook.buck.stdOutLogLevel";
  private static final String STD_ERR_LOG_LEVEL_PROPERTY = "com.facebook.buck.stdErrLogLevel";

  /** @return Directory to use to write test results to. */
  abstract Optional<Path> getDirectoryForTestResults();

  /**
   * @return Path to newline-separated file containing classpath entries with which to run the JVM.
   */
  abstract Path getClasspathFile();

  /** @return The type of test runner to use. */
  abstract TestType getTestType();

  /**
   * @return If true, suspend the JVM to allow a debugger to attach after launch. Defaults to false.
   */
  @Value.Default
  boolean isDebugEnabled() {
    return false;
  }

  /**
   * @return If true, gathers code coverage metrics when running tests.
   *     <p>Defaults to false.
   */
  @Value.Default
  boolean isCodeCoverageEnabled() {
    return false;
  }

  /**
   * @return If true, passes inclNoLocationClassesEnabled=true to the jacoco java agent.
   *     <p>Defaults to false.
   */
  @Value.Default
  boolean isInclNoLocationClassesEnabled() {
    return false;
  }

  /** @return If true, include explanations for tests that were filtered out. */
  @Value.Default
  boolean isShouldExplainTestSelectorList() {
    return false;
  }

  /** @return The filesystem path to a JVM agent (i.e., a profiler). */
  abstract Optional<String> getPathToJavaAgent();

  /** @return Unique identifier for the build. */
  abstract BuildId getBuildId();

  /** @return The filesystem path to the source code for the Buck module under test. */
  abstract Path getBuckModuleBaseSourceCodePath();

  /**
   * @return If set, specifies a minimum Java logging level at and above which java.util.logging
   *     logs will be written to stdout.
   */
  abstract Optional<Level> getStdOutLogLevel();

  /**
   * @return If set, specifies a minimum Java logging level at and above which java.util.logging
   *     logs will be written to stderr.
   */
  abstract Optional<Level> getStdErrLogLevel();

  /**
   * @return If set, specifies a filesystem path to which Robolectric debug logs will be written
   *     during the test.
   */
  abstract Optional<Path> getRobolectricLogPath();

  /** @return The filesystem path to the compiled Buck test runner classes. */
  abstract Path getTestRunnerClasspath();

  /** @return Extra JVM args to pass on the command line. */
  abstract Optional<ImmutableList<String>> getExtraJvmArgs();

  /** @return Name of test classes to run. */
  abstract ImmutableList<String> getTestClasses();

  /** @return Test selectors with which to filter the tests to run. */
  abstract Optional<TestSelectorList> getTestSelectorList();

  /** Formats the JVM arguments in this object suitable to pass on the command line. */
  public void formatCommandLineArgsToList(
      ImmutableList.Builder<String> args,
      ProjectFilesystem filesystem,
      Verbosity verbosity,
      long defaultTestTimeoutMillis) {
    // NOTE(agallagher): These probably don't belong here, but buck integration tests need
    // to find the test runner classes, so propagate these down via the relevant properties.
    args.add(String.format("-Dbuck.testrunner_classes=%s", getTestRunnerClasspath()));

    if (isCodeCoverageEnabled()) {
      args.add(
          String.format(
              "-javaagent:%s=destfile=%s/%s,append=true,inclnolocationclasses=%b",
              JacocoConstants.PATH_TO_JACOCO_AGENT_JAR,
              JacocoConstants.getJacocoOutputDir(filesystem),
              JacocoConstants.JACOCO_EXEC_COVERAGE_FILE,
              isInclNoLocationClassesEnabled()));
    }

    if (getPathToJavaAgent().isPresent()) {
      args.add(String.format("-agentpath:%s", getPathToJavaAgent().get()));
    }

    // Include the buildId
    args.add(String.format("-D%s=%s", BUILD_ID_PROPERTY, getBuildId()));

    // Include the baseDir
    args.add(
        String.format("-D%s=%s", MODULE_BASE_PATH_PROPERTY, getBuckModuleBaseSourceCodePath()));

    // Disable the Java icon from appearing in the OS X Dock while running tests
    args.add("-Dapple.awt.UIElement=true");

    // Include log levels
    if (getStdOutLogLevel().isPresent()) {
      args.add(String.format("-D%s=%s", STD_OUT_LOG_LEVEL_PROPERTY, getStdOutLogLevel().get()));
    }
    if (getStdErrLogLevel().isPresent()) {
      args.add(String.format("-D%s=%s", STD_ERR_LOG_LEVEL_PROPERTY, getStdErrLogLevel().get()));
    }

    if (getRobolectricLogPath().isPresent()) {
      args.add(String.format("-Drobolectric.logging=%s", getRobolectricLogPath().get()));
    }

    if (isDebugEnabled()) {
      // This is the default config used by IntelliJ. By doing this, all a user
      // needs to do is create a new "Remote" debug config. Note that we start
      // suspended, so tests will not run until the user connects.
      args.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005");
    }

    // User-defined VM arguments, such as -D or -X.
    if (getExtraJvmArgs().isPresent()) {
      args.addAll(getExtraJvmArgs().get());
    }

    // verbose flag, if appropriate.
    if (verbosity.shouldUseVerbosityFlagIfAvailable()) {
      args.add("-verbose");
    }

    args.add(
        "-classpath",
        "@"
            + filesystem.resolve(getClasspathFile()).toString()
            + File.pathSeparator
            + getTestRunnerClasspath().toString());

    args.add(FileClassPathRunner.class.getName());

    // Specify the Java class whose main() method should be run. This is the class that is
    // responsible for running the tests.
    args.add(getTestType().getDefaultTestRunner());

    // The first argument to the test runner is where the test results should be written. It is not
    // reliable to write test results to stdout or stderr because there may be output from the unit
    // tests written to those file descriptors, as well.
    if (getDirectoryForTestResults().isPresent()) {
      args.add("--output", getDirectoryForTestResults().get().toString());
    }

    // Add the default test timeout if --debug flag is not set
    long timeout = isDebugEnabled() ? 0 : defaultTestTimeoutMillis;
    args.add("--default-test-timeout", String.valueOf(timeout));

    // Add the test selectors, one per line, in a single argument.
    StringBuilder selectorsArgBuilder = new StringBuilder();
    if (getTestSelectorList().isPresent() && !getTestSelectorList().get().isEmpty()) {
      for (String rawSelector : getTestSelectorList().get().getRawSelectors()) {
        selectorsArgBuilder.append(rawSelector).append("\n");
      }
      args.add("--test-selectors", selectorsArgBuilder.toString());
      if (isShouldExplainTestSelectorList()) {
        args.add("--explain-test-selectors");
      }
    }

    // List all of the tests to be run.
    for (String testClassName : getTestClasses()) {
      args.add(testClassName);
    }
  }
}
