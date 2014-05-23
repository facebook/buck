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

package com.facebook.buck.test.result.groups;

import static org.junit.Assert.fail;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.junit.rules.ExternalResource;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class VacationFixture extends ExternalResource {

  // Regular expression to match test-result lines: the first group is PASS, FAIL, etc., the last
  // is the full test class name.
  public static final Pattern RESULT_LINE_PATTERN = Pattern.compile(
      "^\\s*(\\w+) .+? (com\\.example\\.\\S+)$",
      Pattern.MULTILINE);

  static final TestName VACATION_TEST = new TestName("com.example.vacation.VacationTest");
  static final TestName LOCATION_TEST = new TestName("com.example.vacation.LocationTest");
  static final TestName READBOOK_TEST = new TestName("com.example.vacation.ReadBookTest");
  static final TestName HAVEMIND_TEST = new TestName("com.example.vacation.HaveMindTest");
  static final TestName PASSPORT_TEST = new TestName("com.example.vacation.PassportTest");
  static final TestName WEEPHOTO_TEST = new TestName("com.example.vacation.WeePhotoTest");
  static final TestName MEDICINE_TEST = new TestName("com.example.vacation.MedicineTest");
  static final TestName CURRENCY_TEST = new TestName("com.example.vacation.CurrencyTest");

  private ProjectWorkspace workspace;
  private DebuggableTemporaryFolder temporaryFolder;

  public VacationFixture(DebuggableTemporaryFolder temporaryFolder) {
    this.temporaryFolder = temporaryFolder;
  }

  @Override
  protected void before() throws Throwable {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "vacation", temporaryFolder);
    workspace.setUp();
  }

  Map<TestName, ResultType> runAllTests() throws IOException {
    return runAllTestsAndParseResults("test", "--all");
  }

  Map<TestName, ResultType> runAllTestsWithDrop() throws IOException {
    return runAllTestsAndParseResults("test", "--all", "--ignore-when-dependencies-fail");
  }

  private Map<TestName, ResultType> runAllTestsAndParseResults(String... args) throws IOException {
    ProjectWorkspace.ProcessResult buckCommandResult = workspace.runBuckCommand(args);
    Map<TestName, ResultType> testResults = parseResults(buckCommandResult);
    return testResults;
  }

  private Map<TestName, ResultType> parseResults(ProjectWorkspace.ProcessResult buckResult) {
    List<TestName> testsSeen = Lists.newArrayList();
    Map<TestName, ResultType> results = Maps.newHashMap();
    String[] lines = buckResult.getStderr().split("\n");
    for (String line : lines) {
      Matcher matcher = RESULT_LINE_PATTERN.matcher(line);
      if (matcher.find()) {
        ResultType resultType = ResultType.valueOf(matcher.group(1));
        TestName test = new TestName(matcher.group(2));
        testsSeen.add(test);
        if (results.containsKey(test)) {
          fail(String.format(
              "After seeing tests {%s}, we got a duplicate results for test class '%s'",
              Joiner.on(", ").join(testsSeen),
              test));
        }
        results.put(test, resultType);
      }
    }
    return results;
  }

  void setTestToFail(TestName testName) throws IOException {
    String flagFile = String.format("flags/%s/pass", testName.toString());
    File file = workspace.getFile(flagFile);
    file.delete();
  }

  enum ResultType { PASS, FAIL, DROP }

  static class TestName {
    protected String name;

    TestName(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }

    @Override
    public boolean equals(Object other) {
      return this.name.equals(((TestName) other).name);
    }

    @Override
    public int hashCode() {
      return name.hashCode();
    }
  }
}
