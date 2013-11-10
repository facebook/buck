/*
 * Copyright 2013-present Facebook, Inc.
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


package com.facebook.buck.plugin.intellij.commands.event;

import com.facebook.buck.plugin.intellij.ui.ProgressNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;

public class TestResultsAvailable extends Event {

  private final ImmutableList<TestCase> testCases;

  private TestResultsAvailable(int timestamp,
                               String buildId,
                               int threadId,
                               ImmutableList<TestCase> testCases) {
    super(EventFactory.TEST_RESULTS_AVAILABLE, timestamp, buildId, threadId);
    this.testCases = Preconditions.checkNotNull(testCases);
  }

  public static TestResultsAvailable factory(JsonObject object,
                                             int timestamp,
                                             String buildId,
                                             int threadId) {
    JsonObject resultsObject = object.get("results").getAsJsonObject();
    JsonArray testCasesObject = resultsObject.get("testCases").getAsJsonArray();
    ImmutableList.Builder<TestCase> testCasesBuilder = ImmutableList.builder();
    for (JsonElement element : testCasesObject) {
      JsonObject testCaseObject = element.getAsJsonObject();
      TestCase testCase = TestCase.factory(testCaseObject);
      testCasesBuilder.add(testCase);
    }
    ImmutableList<TestCase> testCases = testCasesBuilder.build();
    return new TestResultsAvailable(timestamp, buildId, threadId, testCases);
  }

  public ImmutableList<ProgressNode> createTreeNodes() {
    ImmutableList.Builder<ProgressNode> builder = ImmutableList.builder();
    for (TestCase testCase : testCases) {
      builder.add(testCase.createTreeNode(this));
    }
    return builder.build();
  }

  private static class TestCase {
    private final String testCaseName;
    private final int totalTime;
    private final boolean success;
    private final ImmutableList<TestResult> testResults;

    private TestCase(String testCaseName,
                     int totalTime,
                     boolean success,
                     ImmutableList<TestResult> testResults) {
      this.testCaseName = Preconditions.checkNotNull(testCaseName);
      this.totalTime = totalTime;
      this.success = success;
      this.testResults = Preconditions.checkNotNull(testResults);
    }

    public static TestCase factory(JsonObject testCase) {
      String testCaseName = testCase.get("testCaseName").getAsString();
      int totalTime = testCase.get("totalTime").getAsInt();
      boolean success = testCase.get("success").getAsBoolean();
      JsonArray testResultsObject = testCase.get("testResults").getAsJsonArray();
      ImmutableList.Builder<TestResult> testResultsBuilder = ImmutableList.builder();
      for (JsonElement element : testResultsObject) {
        JsonObject testResultObject = element.getAsJsonObject();
        TestResult testResult = TestResult.factory(testResultObject);
        testResultsBuilder.add(testResult);
      }
      ImmutableList<TestResult> testResults = testResultsBuilder.build();
      return new TestCase(testCaseName, totalTime, success, testResults);
    }

    public ProgressNode createTreeNode(TestResultsAvailable event) {
      ProgressNode testCaseNode;
      if (success) {
        String title = String.format("[%d ms] %s", totalTime, testCaseName);
        testCaseNode = new ProgressNode(ProgressNode.Type.TEST_CASE_SUCCESS, title, event);
      } else {
        testCaseNode = new ProgressNode(ProgressNode.Type.TEST_CASE_FAILURE, testCaseName, event);
      }
      for (TestResult testResult : testResults) {
        ProgressNode testResultNode = testResult.createTreeNode(event);
        testCaseNode.add(testResultNode);
      }
      return testCaseNode;
    }

    private static class TestResult {
      private final String testName;
      private final boolean success;
      private final int time;
      @Nullable
      private final String message;
      @Nullable
      @SuppressWarnings("unused")
      private final String stacktrace;
      @Nullable
      @SuppressWarnings("unused")
      private final String stdOut;
      @Nullable
      @SuppressWarnings("unused")
      private final String stdErr;

      private TestResult(String testName,
                         boolean success,
                         int time,
                         String message,
                         String stacktrace,
                         String stdOut,
                         String stdErr) {
        this.testName = Preconditions.checkNotNull(testName);
        this.success = success;
        this.time = time;
        this.message = message;
        this.stacktrace = stacktrace;
        this.stdOut = stdOut;
        this.stdErr = stdErr;
      }

      public static TestResult factory(JsonObject testResult) {
        String testName = testResult.get("testName").getAsString();
        boolean success = testResult.get("success").getAsBoolean();
        int time = testResult.get("time").getAsInt();
        String message = null;
        if (!testResult.get("message").isJsonNull()) {
          message = testResult.get("message").getAsString();
        }
        String stacktrace = null;
        if (!testResult.get("stacktrace").isJsonNull()) {
          stacktrace = testResult.get("stacktrace").getAsString();
        }
        String stdOut = null;
        if (!testResult.get("stdOut").isJsonNull()) {
          stdOut = testResult.get("stdOut").getAsString();
        }
        String stdErr = null;
        if (!testResult.get("stdErr").isJsonNull()) {
          stdErr = testResult.get("stdErr").getAsString();
        }
        return new TestResult(testName, success, time, message, stacktrace, stdOut, stdErr);
      }

      public ProgressNode createTreeNode(TestResultsAvailable event) {
        if (success) {
          String title = String.format("[%d ms] %s", time, testName);
          return new ProgressNode(ProgressNode.Type.TEST_RESULT_SUCCESS, title, event);
        } else {
          String title = String.format("%s %s", testName, message);
          return new ProgressNode(ProgressNode.Type.TEST_RESULT_FAILURE, title, event);
        }
      }
    }
  }
}
