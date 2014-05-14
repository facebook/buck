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

package com.facebook.buck.test;

import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.TimeFormat;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
public class TestResultSummary {

  private final String testCaseName;

  private final String testName;

  private final ResultType type;

  private final long time;

  @Nullable
  private final String message;

  @Nullable
  private final String stacktrace;

  @Nullable
  private final String stdOut;

  @Nullable
  private final String stdErr;

  public TestResultSummary(
      String testCaseName,
      String testName,
      ResultType type,
      long time,
      @Nullable String message,
      @Nullable String stacktrace,
      @Nullable String stdOut,
      @Nullable String stdErr) {
    this.testCaseName = testCaseName;
    this.testName = testName;
    this.type = type;
    this.time = time;
    this.message = message;
    this.stacktrace = stacktrace;
    this.stdOut = stdOut;
    this.stdErr = stdErr;
  }

  @JsonCreator
  public static TestResultSummary fromJson(
      @JsonProperty("testCaseName") String testCaseName,
      @JsonProperty("testCase") String testName,
      @JsonProperty("type") String type,
      @JsonProperty("time") long time,
      @JsonProperty("message") @Nullable String message,
      @JsonProperty("stacktrace") @Nullable String stacktrace,
      @JsonProperty("stdOut") @Nullable String stdOut,
      @JsonProperty("stdErr") @Nullable String stdErr) {
    return new TestResultSummary(
        testCaseName,
        testName,
        ResultType.valueOf(type),
        time,
        message,
        stacktrace,
        stdOut,
        stdErr);
  }

  public String getTestName() {
    return testName;
  }

  @JsonIgnore
  public String getTestCaseName() {
    return testCaseName;
  }

  /**
   * For now "success" means "not failure", which introduces the least surprise for tests that have
   * an assumption violation / failure.  Tests that fall into this category are still considered
   * "successful" by buck, though other parts of the system (specifically, event listeners) can do
   * differently if they please.
   */
  @JsonIgnore
  public boolean isSuccess() {
    return type != ResultType.FAILURE;
  }

  public ResultType getType() {
    return type;
  }

  /** @return how long the test took, in milliseconds */
  public long getTime() {
    return time;
  }

  @Nullable public String getMessage() {
    return message;
  }

  @Nullable public String getStacktrace() {
    return stacktrace;
  }

  @Nullable public String getStdOut() {
    return stdOut;
  }

  @Nullable public String getStdErr() {
    return stdErr;
  }

  @Override
  public String toString() {
    return String.format("%s %s %s#%s()",
        isSuccess() ? "PASS" : "FAIL",
        TimeFormat.formatForConsole(getTime(), Ansi.withoutTty()),
        testCaseName,
        getTestName());
  }
}
