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
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class CxxGtestTest extends CxxTest implements HasRuntimeDeps {

  private static final Pattern START = Pattern.compile("^\\[\\s*RUN\\s*\\] (.*)$");
  private static final Pattern END = Pattern.compile("^\\[\\s*(FAILED|OK)\\s*\\] .*");

  private final SourcePath binary;
  private final ImmutableSortedSet<BuildRule> additionalDeps;

  public CxxGtestTest(
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
        "--gtest_color=no",
        "--gtest_output=xml:" + resolvedOutput);
  }

  @Override
  protected ImmutableList<TestResultSummary> parseResults(
      ExecutionContext context,
      Path exitCode,
      Path output,
      Path results)
      throws Exception {

    ImmutableList.Builder<TestResultSummary> summariesBuilder = ImmutableList.builder();

    List<String> outputLines = context.getProjectFilesystem().readLines(output);
    Optional<String> currentTest = Optional.absent();
    Map<String, List<String>> stdout = Maps.newHashMap();
    for (String line : outputLines) {
      Matcher matcher;
      if ((matcher = START.matcher(line.trim())).matches()) {
        String test = matcher.group(1);
        currentTest = Optional.of(test);
        stdout.put(test, Lists.<String>newArrayList());
      } else if (END.matcher(line.trim()).matches()) {
        currentTest = Optional.absent();
      } else if (currentTest.isPresent()) {
        stdout.get(currentTest.get()).add(line);
      }
    }

    Document doc = XmlDomParser.parse(results.toFile());
    Node testsuites = doc.getElementsByTagName("testsuites").item(0);
    Node testsuite = testsuites.getChildNodes().item(1);
    NodeList testcases = testsuite.getChildNodes();
    for (int index = 1; index < testcases.getLength(); index += 2) {
      Node testcase = testcases.item(index);
      NamedNodeMap attributes = testcase.getAttributes();
      String testCase = attributes.getNamedItem("classname").getNodeValue();
      String testName = attributes.getNamedItem("name").getNodeValue();
      String testFull = String.format("%s.%s", testCase, testName);
      Double time = Double.parseDouble(attributes.getNamedItem("time").getNodeValue()) * 1000;
      ResultType type = ResultType.SUCCESS;
      String message = "";
      if (testcase.getChildNodes().getLength() > 0) {
        Node failure = testcase.getChildNodes().item(1);
        type = ResultType.FAILURE;
        message = failure.getAttributes().getNamedItem("message").getNodeValue();
      }
      summariesBuilder.add(
          new TestResultSummary(
              testCase,
              testName,
              type,
              time.longValue(),
              message,
              "",
              Joiner.on(System.lineSeparator()).join(stdout.get(testFull)),
              ""));
    }

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
