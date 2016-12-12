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

package com.facebook.buck.rust;

import static com.facebook.buck.rust.RustLinkables.extendLinkerArgs;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.ExternalTestRunnerRule;
import com.facebook.buck.rules.ExternalTestRunnerTestSpec;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Charsets;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class RustTest
    extends RustCompile
    implements BinaryBuildRule, TestRule, ExternalTestRunnerRule {
  private static final Pattern TEST_STDOUT_PATTERN = Pattern.compile(
      "^---- (?<name>.+) stdout ----$");
  private static final Pattern FAILURES_LIST_PATTERN = Pattern.compile(
      "^failures:$");
  private final String crate;
  private final Path testOutputFile;
  private final Path testStdoutFile;

  public RustTest(
      BuildRuleParams params,
      SourcePathResolver resolver,
      String crate,
      Optional<SourcePath> crateRoot,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<String> features,
      ImmutableList<String> rustcFlags,
      Supplier<Tool> compiler,
      Supplier<Tool> linker,
      ImmutableList<String> linkerArgs,
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType linkStyle) throws NoSuchBuildTargetException {
    super(
        RustLinkables.addNativeDependencies(params, resolver, cxxPlatform, linkStyle),
        resolver,
        crate,
        crateRoot,
        srcs,
        ImmutableList.<String>builder()
            .add("--test")
            .addAll(rustcFlags)
            .build(),
        features,
        RustLinkables.getNativeDirs(params.getDeps(), linkStyle, cxxPlatform),
        BuildTargets.getGenPath(
            params.getProjectFilesystem(),
            params.getBuildTarget(),
            "%s").resolve(crate),
        compiler,
        linker,
        extendLinkerArgs(
            linkerArgs,
            params.getDeps(),
            linkStyle,
            cxxPlatform),
        linkStyle);

    this.crate = crate;
    this.testOutputFile = getProjectFilesystem().resolve(getPathToTestResults());
    this.testStdoutFile = getProjectFilesystem().resolve(getPathToTestStdout());
  }

  @Override
  public boolean hasTestResultFiles() {
    return false;
  }

  @Override
  public ImmutableList<Step> runTests(
      ExecutionContext executionContext,
      TestRunningOptions options,
      TestReportingCallback testReportingCallback) {
    Path workingDirectory = getProjectFilesystem().resolve(getPathToTestOutputDirectory());
    return ImmutableList.of(
        new RustTestStep(
            getProjectFilesystem(),
            workingDirectory,
            getTestCommand("--logfile", testOutputFile.toString()),
            ImmutableMap.of(), // TODO(StanislavGlebik): environment
            workingDirectory.resolve("exitcode"),
            Optional.empty(),
            this.testStdoutFile
        )
    );
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      ExecutionContext executionContext, boolean isUsingTestSelectors) {
    return () -> {
      ImmutableList<TestCaseSummary> summaries = ImmutableList.of();
      summaries = ImmutableList.of(
          new TestCaseSummary(
              getBuildTarget().getFullyQualifiedName(),
              parseTestResults()));
      return TestResults.of(
          getBuildTarget(),
          summaries,
          getContacts(),
          getLabels().stream()
              .map(Object::toString)
              .collect(MoreCollectors.toImmutableSet()));
    };
  }

  @Override
  public ImmutableSet<Label> getLabels() {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<String> getContacts() {
    // TODO(StanislavGlebik)
    return ImmutableSet.of();
  }

  @Override
  protected ImmutableSet<String> getDefaultSources() {
    return ImmutableSet.of("main.rs", "lib.rs");
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(),
        getBuildTarget(),
        "%s");
  }

  @Override
  public boolean runTestSeparately() {
    return false;
  }

  @Override
  public boolean supportsStreamingTests() {
    return false;
  }

  @Override
  public ExternalTestRunnerTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext, TestRunningOptions testRunningOptions) {
    return ExternalTestRunnerTestSpec.builder()
        .setTarget(getBuildTarget())
        .setType("rust")
        .addAllCommand(getTestCommand())
        .addAllLabels(getLabels())
        .addAllContacts(getContacts())
        .build();
  }

  private ImmutableList<String> getTestCommand(String... additionalArgs) {
    Path workingDirectory = getProjectFilesystem().resolve(getPathToTestOutputDirectory());
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add(workingDirectory.toAbsolutePath().resolve(crate).toString());
    args.add(additionalArgs);
    return args.build();
  }

  private Path getPathToTestResults() {
    return getPathToTestOutputDirectory().resolve("output");
  }

  private Path getPathToTestStdout() {
    return getPathToTestOutputDirectory().resolve("stdout");
  }

  private ImmutableList<TestResultSummary> parseTestResults() throws IOException {

    Map<String, ResultType> testToResult = new HashMap<>();
    try (BufferedReader reader = Files.newBufferedReader(testOutputFile, Charsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] resultAndTestName = line.split(" ");
        if (resultAndTestName.length != 2) {
          throw new RuntimeException(String.format("Unknown test output format %s", line));
        }
        ResultType result;
        if (resultAndTestName[0].equals("ok")) {
          result = ResultType.SUCCESS;
        } else if (resultAndTestName[0].equals("failed")) {
          result = ResultType.FAILURE;
        } else if (resultAndTestName[0].equals("ignored")) {
          result = ResultType.DISABLED;
        } else {
          throw new RuntimeException(String.format("Unknown test status %s", line));
        }
        testToResult.put(resultAndTestName[1], result);
      }
    }

    Map<String, String> testToStdout = new HashMap<>();
    try (BufferedReader reader = Files.newBufferedReader(testStdoutFile, Charsets.UTF_8)) {

      StringBuilder stdout = new StringBuilder();
      String currentStdoutTestName = null;
      BiConsumer<String, String> addTestStdout = (key, value) -> {
        testToStdout.put(key, value);
        stdout.setLength(0);
      };
      String line;
      while ((line = reader.readLine()) != null) {
        Matcher matcher;
        if ((matcher = TEST_STDOUT_PATTERN.matcher(line)).matches()) {
          if (currentStdoutTestName != null) {
            // Start of stdout output for new test
            // Save current stdout
            addTestStdout.accept(currentStdoutTestName, stdout.toString());
          }
          currentStdoutTestName = matcher.group("name");
        } else if (FAILURES_LIST_PATTERN.matcher(line).matches()) {
          if (currentStdoutTestName != null) {
            addTestStdout.accept(currentStdoutTestName, stdout.toString());
          }
        } else if (currentStdoutTestName != null) {
          stdout.append("\n");
          stdout.append(line);
        }
      }
    }

    ImmutableList.Builder<TestResultSummary> summariesBuilder = ImmutableList.builder();
    for (Map.Entry<String, ResultType> entry : testToResult.entrySet()) {
      summariesBuilder.add(
          new TestResultSummary(
              "rust_test",
              entry.getKey(),
              entry.getValue(),
              0, // TODO(StanislavGlebik) time
              "", // message
              "", // stack trace,
              testToStdout.get(entry.getKey()),
              "" // stderr
          ));
    }
    return summariesBuilder.build();
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder()
        .addArg(new SourcePathArg(getResolver(), new BuildTargetSourcePath(getBuildTarget())))
        .build();
  }
}
