/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.python;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.test.selectors.TestDescription;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.file.Path;

public class PythonRunTestsStep implements Step {
  private static final CharMatcher PYTHON_RE_REGULAR_CHARACTERS = CharMatcher.anyOf(
      "_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ01234567890");

  private final Path workingDirectory;
  private final String testName;
  private final ImmutableList<String> commandPrefix;
  private final Supplier<ImmutableMap<String, String>> environment;
  private final TestSelectorList testSelectorList;
  private final Optional<Long> testRuleTimeoutMs;
  private final Path resultsOutputPath;

  private boolean timedOut;

  public PythonRunTestsStep(
      Path workingDirectory,
      String testName,
      ImmutableList<String> commandPrefix,
      Supplier<ImmutableMap<String, String>> environment,
      TestSelectorList testSelectorList,
      Optional<Long> testRuleTimeoutMs,
      Path resultsOutputPath) {
    this.workingDirectory = workingDirectory;
    this.testName = testName;
    this.commandPrefix = commandPrefix;
    this.environment = environment;
    this.testSelectorList = testSelectorList;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.resultsOutputPath = resultsOutputPath;

    this.timedOut = false;
  }

  @Override
  public int execute(ExecutionContext context) throws IOException, InterruptedException {
    int exitCode = doExecute(context);
    if (timedOut) {
      throw new HumanReadableException(
          "Following test case timed out: " + testName + ", with exitCode: " + exitCode);
    }
    return exitCode;
  }

  private int doExecute(ExecutionContext context) throws IOException, InterruptedException {
    if (testSelectorList.isEmpty()) {
      return getShellStepWithArgs("-o", resultsOutputPath.toString()).execute(context);
    }

    ShellStep listStep = getShellStepWithArgs("-l", "-L", "buck");
    int exitCode = listStep.execute(context);
    if (timedOut || exitCode != 0) {
      return exitCode;
    }

    String testsToRunRegex = getTestsToRunRegexFromListOutput(listStep.getStdout());

    return getShellStepWithArgs(
        "--hide-output",
        "-o", resultsOutputPath.toString(),
        "-r", testsToRunRegex).execute(context);
  }

  private String getTestsToRunRegexFromListOutput(String listOutput) {
    ImmutableList.Builder<String> testsToRunPatternComponents = ImmutableList.builder();

    for (String strTestCase : CharMatcher.whitespace().trimFrom(listOutput).split("\n")) {
      String[] testCase = CharMatcher.whitespace().trimFrom(strTestCase).split("#", 2);
      if (testCase.length != 2) {
        throw new RuntimeException(String.format(
            "Bad test case name from python runner: '%s'", strTestCase));
      }

      TestDescription testDescription = new TestDescription(testCase[0], testCase[1]);
      if (testSelectorList.isIncluded(testDescription)) {
        testsToRunPatternComponents.add(escapeForPythonRegex(strTestCase));
      }
    }

    return "^" + Joiner.on('|').join(testsToRunPatternComponents.build()) + "$";
  }

  // Escapes a string for python's re module. Note Pattern.quote uses \Q and \E which do not exist
  // in python.
  // This is based on https://github.com/python/cpython/blob/2.7/Lib/re.py#L208 .
  private String escapeForPythonRegex(String s) {
    StringBuilder result = new StringBuilder((int) (s.length() * 1.3));
    for (char c : s.toCharArray()) {
      if (!PYTHON_RE_REGULAR_CHARACTERS.matches(c)) {
        result.append('\\');
      }
      result.append(c);
    }
    return result.toString();
  }

  @Override
  public String getShortName() {
    return "pyunit";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    if (testSelectorList.isEmpty()) {
      return getShellStepWithArgs("-o", resultsOutputPath.toString()).getDescription(context);
    }

    return getShellStepWithArgs(
        "-o", resultsOutputPath.toString(), "-r", "<matching tests>").getDescription(context);
  }

  private ShellStep getShellStepWithArgs(final String... args) {
    return new ShellStep(workingDirectory) {
      @Override
      protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
        return ImmutableList.<String>builder().addAll(commandPrefix).add(args).build();
      }

      @Override
      public String getShortName() {
        throw new UnsupportedOperationException();
      }

      @Override
      public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
        return environment.get();
      }

      @Override
      protected Optional<Long> getTimeout() {
        return testRuleTimeoutMs;
      }

      @Override
      protected Optional<Function<Process, Void>> getTimeoutHandler(ExecutionContext context) {
        return Optional.<Function<Process, Void>>of(
            new Function<Process, Void>() {
              @Override
              public Void apply(Process input) {
                timedOut = true;
                return null;
              }
            });
      }
    };
  }
}
