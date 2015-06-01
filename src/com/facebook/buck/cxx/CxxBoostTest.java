/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.XmlDomParser;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class CxxBoostTest extends CxxTest implements HasRuntimeDeps {

  private static final Pattern SUITE_START = Pattern.compile("^Entering test suite \"(.*)\"$");
  private static final Pattern SUITE_END = Pattern.compile("^Leaving test suite \"(.*)\"$");

  private static final Pattern CASE_START = Pattern.compile("^Entering test case \"(.*)\"$");
  private static final Pattern CASE_END = Pattern.compile(
      "^Leaving test case \"(.*)\"(?:; testing time: (\\d+)ms)?$");

  private static final Pattern ERROR = Pattern.compile("^.*\\(\\d+\\): error .*");

  private final SourcePath binary;
  private final ImmutableSortedSet<BuildRule> additionalDeps;

  public CxxBoostTest(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePath binary,
      ImmutableSortedSet<BuildRule> additionalDeps,
      ImmutableSet<Label> labels,
      ImmutableSet<String> contacts,
      ImmutableSet<BuildRule> sourceUnderTest) {
    super(params, resolver, labels, contacts, sourceUnderTest);
    this.binary = binary;
    this.additionalDeps = additionalDeps;
  }

  @Override
  protected ImmutableList<String> getShellCommand(
      ExecutionContext context,
      Path output) {
    ProjectFilesystem filesystem = context.getProjectFilesystem();
    String resolvedBinary = filesystem.resolve(getResolver().getPath(binary)).toString();
    String resolvedOutput = filesystem.resolve(output).toString();
    return ImmutableList.of(
        resolvedBinary,
        "--log_format=hrf",
        "--log_level=test_suite",
        "--report_format=xml",
        "--report_level=detailed",
        "--result_code=no",
        "--report_sink=" + resolvedOutput);
  }

  private void visitTestSuite(
      ImmutableList.Builder<TestResultSummary> builder,
      Map<String, String> messages,
      Map<String, List<String>> stdout,
      Map<String, Long> times,
      String prefix,
      Node testSuite) {

    NamedNodeMap attributes = testSuite.getAttributes();

    String suiteName = attributes.getNamedItem("name").getNodeValue();
    if (!prefix.isEmpty()) {
      suiteName = prefix + "." + suiteName;
    }

    NodeList testCases = testSuite.getChildNodes();
    for (int index = 0; index < testCases.getLength(); index++) {
      Node testCase = testCases.item(index);

      if (!testCase.getNodeName().equals("TestCase")) {
        visitTestSuite(builder, messages, stdout, times, suiteName, testCase);
        continue;
      }

      NamedNodeMap attrs = testCase.getAttributes();
      String caseName = attrs.getNamedItem("name").getNodeValue();
      String test = String.format("%s.%s", suiteName, caseName);
      Long time = Optional.fromNullable(times.get(test)).or(0L);
      String resultString = attrs.getNamedItem("result").getNodeValue();
      ResultType result = ResultType.SUCCESS;
      String output = "";
      String message = "";
      if (!"passed".equals(resultString)) {
        result = ResultType.FAILURE;
        message = messages.get(test);
        output = Joiner.on("\n").join(stdout.get(test));
      }
      builder.add(
          new TestResultSummary(
              suiteName,
              caseName,
              result,
              time,
              message,
              /* stacktrace */ "",
              /* stdOut */ output,
              /* stdErr */ ""));
    }
  }

  @Override
  protected ImmutableList<TestResultSummary> parseResults(
      ExecutionContext context,
      Path exitCode,
      Path output,
      Path results)
      throws Exception {

    ImmutableList.Builder<TestResultSummary> summariesBuilder = ImmutableList.builder();

    // Process the test run output to grab the individual test stdout/stderr and
    // test run times.
    Map<String, String> messages = Maps.newHashMap();
    Map<String, List<String>> stdout = Maps.newHashMap();
    Map<String, Long> times = Maps.newHashMap();
    try (BufferedReader reader = Files.newBufferedReader(output, Charsets.US_ASCII)) {
      Stack<String> testSuite = new Stack<>();
      Optional<String> currentTest = Optional.absent();
      String line;
      while ((line = reader.readLine()) != null) {
        Matcher matcher;
        if ((matcher = SUITE_START.matcher(line)).matches()) {
          String suite = matcher.group(1);
          testSuite.push(suite);
        } else if ((matcher = SUITE_END.matcher(line)).matches()) {
          String suite = matcher.group(1);
          Preconditions.checkState(testSuite.peek().equals(suite));
          testSuite.pop();
        } else if ((matcher = CASE_START.matcher(line)).matches()) {
          String test = Joiner.on(".").join(testSuite) + "." + matcher.group(1);
          currentTest = Optional.of(test);
          stdout.put(test, Lists.<String>newArrayList());
        } else if ((matcher = CASE_END.matcher(line)).matches()) {
          String test = Joiner.on(".").join(testSuite) + "." + matcher.group(1);
          Preconditions.checkState(currentTest.isPresent() && currentTest.get().equals(test));
          String time = matcher.group(2);
          times.put(test, time == null ? 0 : Long.valueOf(time));
          currentTest = Optional.absent();
        } else if (currentTest.isPresent()) {
          if (ERROR.matcher(line).matches()) {
            messages.put(currentTest.get(), line);
          } else {
            stdout.get(currentTest.get()).add(line);
          }
        }
      }
    }

    // Parse the XML result file for the actual test result summaries.
    Document doc = XmlDomParser.parse(results.toFile());
    Node testResult = doc.getElementsByTagName("TestResult").item(0);
    Node testSuite = testResult.getFirstChild();
    visitTestSuite(summariesBuilder, messages, stdout, times, "", testSuite);

    return summariesBuilder.build();
  }

  // The C++ test rules just wrap a test binary produced by another rule, so make sure that's
  // always available to run the test.
  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(getResolver().getRule(binary).asSet())
        .addAll(additionalDeps)
        .build();
  }

}
