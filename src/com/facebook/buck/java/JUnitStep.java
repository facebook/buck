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

import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.BuckConstant;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class JUnitStep extends ShellStep {

  public static final Path EMMA_OUTPUT_DIR = BuckConstant.GEN_PATH.resolve("emma");

  // Note that the default value is used when `buck test --all` is run on Buck itself.
  // TODO(mbolin): Change this so that pathToEmmaJar is injected. This is a non-trivial refactor
  // because a number of other classes currently reference this constant.
  public static final String PATH_TO_EMMA_JAR = System.getProperty("buck.path_to_emma_jar",
      "third-party/java/emma-2.0.5312/out/emma-2.0.5312.jar");

  @VisibleForTesting
  static final String JUNIT_TEST_RUNNER_CLASS_NAME =
      "com.facebook.buck.junit.JUnitRunner";

  private static final String EMMA_COVERAGE_OUT_FILE = "emma.coverage.out.file";

  private final Set<String> classpathEntries;

  private final Set<String> testClassNames;

  private final List<String> vmArgs;

  private final String directoryForTestResults;

  private final boolean isCodeCoverageEnabled;

  private final boolean isDebugEnabled;

  private Optional<TestSelectorList> testSelectorListOptional;

  private final String testRunnerClassesDirectory;

  /**
   *  If EMMA is not enabled, then JaCoco is enabled for the code-coverage analysis.
   */
  public final boolean isJacocoEnabled;

  public static final String PATH_TO_JACOCO_JARS = System.getProperty("buck.path_to_jacoco_jars",
      "third-party/java/jacoco-0.6.4/out");

  public static final String PATH_TO_JACOCO_AGENT_JAR = String.format("%s/%s",
      PATH_TO_JACOCO_JARS,
      System.getProperty("buck.jacoco_agent_jar", "jacocoagent.jar"));

  public static final String JACOCO_EXEC_COVERAGE_FILE = "jacoco.exec";

  public static final Path JACOCO_OUTPUT_DIR = BuckConstant.GEN_PATH.resolve("jacoco");

  /**
   * @param classpathEntries contains the entries that will be listed first in the classpath when
   *     running JUnit. Entries for the bootclasspath for Android will be appended to this list, as
   *     well as an entry for the test runner. classpathEntries must include entries for the tests
   *     that will be run, as well as an entry for JUnit.
   * @param testClassNames the fully qualified names of the Java tests to run
   * @param directoryForTestResults directory where test results should be written
   */
  public JUnitStep(
      Set<String> classpathEntries,
      Set<String> testClassNames,
      List<String> vmArgs,
      String directoryForTestResults,
      boolean isCodeCoverageEnabled,
      boolean isJacocoEnabled,
      boolean isDebugEnabled,
      Optional<TestSelectorList> testSelectorListOptional) {
    this(classpathEntries,
        testClassNames,
        vmArgs,
        directoryForTestResults,
        isCodeCoverageEnabled,
        isJacocoEnabled,
        isDebugEnabled,
        testSelectorListOptional,
        System.getProperty("buck.testrunner_classes",
            new File("build/testrunner/classes").getAbsolutePath()));
  }

  @VisibleForTesting
  JUnitStep(
      Set<String> classpathEntries,
      Set<String> testClassNames,
      List<String> vmArgs,
      String directoryForTestResults,
      boolean isCodeCoverageEnabled,
      boolean isJacocoEnabled,
      boolean isDebugEnabled,
      Optional<TestSelectorList> testSelectorListOptional,
      String testRunnerClassesDirectory) {
    this.classpathEntries = ImmutableSet.copyOf(classpathEntries);
    this.testClassNames = ImmutableSet.copyOf(testClassNames);
    this.vmArgs = ImmutableList.copyOf(vmArgs);
    this.directoryForTestResults = Preconditions.checkNotNull(directoryForTestResults);
    this.isCodeCoverageEnabled = isCodeCoverageEnabled;
    this.isJacocoEnabled = isJacocoEnabled;
    this.isDebugEnabled = isDebugEnabled;
    this.testSelectorListOptional = testSelectorListOptional;
    this.testRunnerClassesDirectory = Preconditions.checkNotNull(testRunnerClassesDirectory);
  }

  @Override
  public String getShortName() {
    return "junit";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("java");

    // Add the output property for EMMA so if the classes are instrumented, coverage.ec will be
    // placed in the EMMA output folder.
    if (isCodeCoverageEnabled) {
      if (!isJacocoEnabled) {
        args.add(String.format("-D%s=%s/coverage.ec", EMMA_COVERAGE_OUT_FILE, EMMA_OUTPUT_DIR));
      } else {
        args.add(String.format("-javaagent:%s=destfile=%s/%s,append=true",
            PATH_TO_JACOCO_AGENT_JAR,
            JACOCO_OUTPUT_DIR,
            JACOCO_EXEC_COVERAGE_FILE));
      }
    }

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

    // Build up the -classpath argument, starting with the classpath entries the client specified.
    List<String> classpath = Lists.newArrayList(classpathEntries);

    // Add EMMA to the classpath.
    if (isCodeCoverageEnabled && !isJacocoEnabled) {
      classpath.add(PATH_TO_EMMA_JAR);
    }

    // Next, add the bootclasspath entries specific to the Android platform being targeted.
    if (context.getAndroidPlatformTargetOptional().isPresent()) {
      AndroidPlatformTarget androidPlatformTarget = context.getAndroidPlatformTarget();
      for (Path bootclasspathEntry : androidPlatformTarget.getBootclasspathEntries()) {
        classpath.add(bootclasspathEntry.toString());
      }
    }

    // Finally, include an entry for the test runner.
    classpath.add(testRunnerClassesDirectory);

    // Add the -classpath argument.
    args.add("-classpath").add(Joiner.on(File.pathSeparator).join(classpath));

    // Specify the Java class whose main() method should be run. This is the class that is
    // responsible for running the tests.
    args.add(JUNIT_TEST_RUNNER_CLASS_NAME);

    // The first argument to the test runner is where the test results should be written. It is not
    // reliable to write test results to stdout or stderr because there may be output from the unit
    // tests written to those file descriptors, as well.
    args.add(directoryForTestResults);

    // Add the default test timeout if --debug flag is not set
    long timeout = isDebugEnabled ? 0 : context.getDefaultTestTimeoutMillis();
    args.add(String.valueOf(timeout));

    // Add the test selectors, one per line, in a single argument.
    StringBuilder selectorsArgBuilder = new StringBuilder();
    if (testSelectorListOptional.isPresent()) {
      TestSelectorList testSelectorList = testSelectorListOptional.get();
      for (String rawSelector : testSelectorList.getRawSelectors()) {
        selectorsArgBuilder.append(rawSelector).append("\n");
      }
    }
    args.add(selectorsArgBuilder.toString());

    // List all of the tests to be run.
    for (String testClassName : testClassNames) {
      args.add(testClassName);
    }

    return args.build();
  }

  private void warnUser(ExecutionContext context, String message) {
    context.getStdErr().println(context.getAnsi().asWarningText(message));
  }

}
