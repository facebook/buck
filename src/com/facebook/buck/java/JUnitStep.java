/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.java;

import com.facebook.buck.java.runner.FileClassPathRunner;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

public class JUnitStep extends ShellStep {

  // Note that the default value is used when `buck test --all` is run on Buck itself.
  @VisibleForTesting
  static final String JUNIT_TEST_RUNNER_CLASS_NAME =
      "com.facebook.buck.junit.JUnitMain";
  @VisibleForTesting
  static final String TESTNG_TEST_RUNNER_CLASS_NAME =
      "com.facebook.buck.junit.TestNGMain";

  private static final Path TESTRUNNER_CLASSES =
      Paths.get(
          System.getProperty(
              "buck.testrunner_classes",
              new File("build/testrunner/classes").getAbsolutePath()));

  @VisibleForTesting
  public static final String BUILD_ID_PROPERTY = "com.facebook.buck.buildId";

  private final ImmutableSet<Path> classpathEntries;
  private final Iterable<String> testClassNames;
  private final List<String> vmArgs;
  private final Path directoryForTestResults;
  private final Path tmpDirectory;
  private final Path testRunnerClasspath;
  private final boolean isCodeCoverageEnabled;
  private final boolean isDebugEnabled;
  private final BuildId buildId;
  private TestSelectorList testSelectorList;
  private final boolean isDryRun;
  private final TestType type;
  private final Optional<Long> testRuleTimeoutMs;

  // Set when the junit command times out.
  private boolean hasTimedOut = false;

  /**
   *  JaCoco is enabled for the code-coverage analysis.
   */
  public static final String PATH_TO_JACOCO_AGENT_JAR =
      System.getProperty(
          "buck.jacoco_agent_jar",
          "third-party/java/jacoco/jacocoagent.jar");

  public static final String JACOCO_EXEC_COVERAGE_FILE = "jacoco.exec";

  public static final Path JACOCO_OUTPUT_DIR = BuckConstant.GEN_PATH.resolve("jacoco");

  /**
   * @param classpathEntries contains the entries that will be listed first in the classpath when
   *     running JUnit. Entries for the bootclasspath for Android will be appended to this list, as
   *     well as an entry for the test runner. classpathEntries must include entries for the tests
   *     that will be run, as well as an entry for JUnit.
   * @param testClassNames the fully qualified names of the Java tests to run
   * @param directoryForTestResults directory where test results should be written
   * @param tmpDirectory directory tests can use for local file scratch space.
   */
  public JUnitStep(
      Set<Path> classpathEntries,
      Iterable<String> testClassNames,
      List<String> vmArgs,
      Path directoryForTestResults,
      Path tmpDirectory,
      boolean isCodeCoverageEnabled,
      boolean isDebugEnabled,
      BuildId buildId,
      TestSelectorList testSelectorList,
      boolean isDryRun,
      TestType type,
      Optional<Long> testRuleTimeoutMs) {
    this(classpathEntries,
        testClassNames,
        vmArgs,
        directoryForTestResults,
        tmpDirectory,
        isCodeCoverageEnabled,
        isDebugEnabled,
        buildId,
        testSelectorList,
        isDryRun,
        type,
        TESTRUNNER_CLASSES,
        testRuleTimeoutMs);
  }

  @VisibleForTesting
  JUnitStep(
      Set<Path> classpathEntries,
      Iterable<String> testClassNames,
      List<String> vmArgs,
      Path directoryForTestResults,
      Path tmpDirectory,
      boolean isCodeCoverageEnabled,
      boolean isDebugEnabled,
      BuildId buildId,
      TestSelectorList testSelectorList,
      boolean isDryRun,
      TestType type,
      Path testRunnerClasspath,
      Optional<Long> testRuleTimeoutMs) {
    this.classpathEntries = ImmutableSet.copyOf(classpathEntries);
    this.testClassNames = Iterables.unmodifiableIterable(testClassNames);
    this.vmArgs = ImmutableList.copyOf(vmArgs);
    this.directoryForTestResults = directoryForTestResults;
    this.tmpDirectory = tmpDirectory;
    this.isCodeCoverageEnabled = isCodeCoverageEnabled;
    this.isDebugEnabled = isDebugEnabled;
    this.buildId = buildId;
    this.testSelectorList = testSelectorList;
    this.isDryRun = isDryRun;
    this.type = type;
    this.testRunnerClasspath = testRunnerClasspath;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
  }

  @Override
  public String getShortName() {
    return "junit";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("java");
    args.add(
        String.format(
            "-Djava.io.tmpdir=%s",
            context.getProjectFilesystem().resolve(tmpDirectory)));

    // NOTE(agallagher): These propbably don't belong here, but buck integration tests need
    // to find the test runner classes, so propagate these down via the relevant properties.
    args.add(
        String.format(
            "-Dbuck.testrunner_classes=%s",
            testRunnerClasspath));

    if (isCodeCoverageEnabled) {
      args.add(String.format("-javaagent:%s=destfile=%s/%s,append=true",
          PATH_TO_JACOCO_AGENT_JAR,
          JACOCO_OUTPUT_DIR,
          JACOCO_EXEC_COVERAGE_FILE));
    }

    // Include the buildId
    args.add(String.format("-D%s=%s", BUILD_ID_PROPERTY, buildId));

    if (isDebugEnabled) {
      // This is the default config used by IntelliJ. By doing this, all a user
      // needs to do is create a new "Remote" debug config. Note that we start
      // suspended, so tests will not run until the user connects.
      args.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005");
      warnUser(context,
          "Debugging. Suspending JVM. Connect a JDWP debugger to port 5005 to proceed.");
    }

    // User-defined VM arguments, such as -D or -X.
    args.addAll(vmArgs);

    // verbose flag, if appropriate.
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      args.add("-verbose");
    }

    // Add the -classpath argument.
    args.add("-classpath").add(
        Joiner.on(File.pathSeparator).join(
            "@" + context.getProjectFilesystem().resolve(getClassPathFile()),
            testRunnerClasspath));

    args.add(FileClassPathRunner.class.getName());

    // Specify the Java class whose main() method should be run. This is the class that is
    // responsible for running the tests.
    if (TestType.JUNIT == type) {
      args.add(JUNIT_TEST_RUNNER_CLASS_NAME);
    } else if (TestType.TESTNG == type) {
      args.add(TESTNG_TEST_RUNNER_CLASS_NAME);
    } else {
      throw new IllegalArgumentException(
          "java_test: unrecognized type " + type + ", expected eg. junit or testng");
    }

    // The first argument to the test runner is where the test results should be written. It is not
    // reliable to write test results to stdout or stderr because there may be output from the unit
    // tests written to those file descriptors, as well.
    args.add(directoryForTestResults.toString());

    // Add the default test timeout if --debug flag is not set
    long timeout = isDebugEnabled ? 0 : context.getDefaultTestTimeoutMillis();
    args.add(String.valueOf(timeout));

    // Add the test selectors, one per line, in a single argument.
    StringBuilder selectorsArgBuilder = new StringBuilder();
    if (!testSelectorList.isEmpty()) {
      for (String rawSelector : this.testSelectorList.getRawSelectors()) {
        selectorsArgBuilder.append(rawSelector).append("\n");
      }
    }
    args.add(selectorsArgBuilder.toString());

    // Dry-run flag.
    args.add(isDryRun ? "non-empty-dry-run-flag" : "");

    // List all of the tests to be run.
    for (String testClassName : testClassNames) {
      args.add(testClassName);
    }

    return args.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return ImmutableMap.of("TMP", context.getProjectFilesystem().resolve(tmpDirectory).toString());
  }

  private void warnUser(ExecutionContext context, String message) {
    context.getStdErr().println(context.getAnsi().asWarningText(message));
  }

  @VisibleForTesting
  protected Path getClassPathFile() {
    return tmpDirectory.resolve("classpath-file");
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    try {
      context.getProjectFilesystem().writeLinesToPath(
          FluentIterable.from(classpathEntries)
              .transform(Functions.toStringFunction()),
          getClassPathFile());
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }
    return super.execute(context);
  }

  @Override
  protected Optional<Long> getTimeout() {
    return testRuleTimeoutMs;
  }

  @Override
  protected int getExitCodeFromResult(ExecutionContext context, ProcessExecutor.Result result) {
    int exitCode = result.getExitCode();

    // If we timed out, force the exit code to 0 just so that the step itself doesn't fail,
    // allowing us to interpret any test cases that finished before the bad test.  We signify
    // this special case by setting `hasTimedOut` which the result interpreter will query to
    // properly format its results.
    if (result.isTimedOut()) {
      exitCode = 0;
      hasTimedOut = true;
    }

    return exitCode;
  }

  public boolean hasTimedOut() {
    return hasTimedOut;
  }

}
