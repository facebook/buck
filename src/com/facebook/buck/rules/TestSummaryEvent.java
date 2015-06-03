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

package com.facebook.buck.rules;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.test.TestResultSummary;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.UUID;

public abstract class TestSummaryEvent extends AbstractBuckEvent implements LeafEvent {

  @JsonIgnore
  private final UUID uuid;

  private final String testCaseName;
  private final String testName;

  private TestSummaryEvent(UUID uuid, String testCaseName, String testName) {
    this.uuid = uuid;
    this.testCaseName = testCaseName;
    this.testName = testName;
  }

  @Override
  public String getCategory() {
    return "test_summary";
  }

  public String getTestCaseName() {
    return testCaseName;
  }

  public String getTestName() {
    return testName;
  }

  public static Started started(
      UUID uuid, String testCaseName, String testName) {
    return new Started(uuid, testCaseName, testName);
  }

  public static Finished finished(
      UUID uuid, TestResultSummary testResultSummary) {
    return new Finished(uuid, testResultSummary);
  }

  @Override
  public boolean isRelatedTo(BuckEvent event) {
    if (!(event instanceof TestSummaryEvent)) {
      return false;
    }

    TestSummaryEvent that = (TestSummaryEvent) event;

    return
        this.uuid.equals(that.uuid) &&
        !getClass().equals(that.getClass());
  }


  public static class Started extends TestSummaryEvent {
    final String testCaseName;
    final String testName;

    public Started(UUID uuid, String testCaseName, String testName) {
      super(uuid, testCaseName, testName);
      this.testCaseName = testCaseName;
      this.testName = testName;
    }

    @Override
    public String getEventName() {
      return "TestStarted";
    }

    @Override
    protected String getValueString() {
      return String.format("Test case %s test %s", testCaseName, testName);
    }
  }

  public static class Finished extends TestSummaryEvent {

    private final TestResultSummary testResultSummary;

    public Finished(UUID uuid, TestResultSummary testResultSummary) {
      super(
          uuid,
          testResultSummary.getTestCaseName(),
          testResultSummary.getTestName());
      this.testResultSummary = testResultSummary;
    }

    public TestResultSummary getTestResultSummary() {
      return testResultSummary;
    }

    @Override
    public String getEventName() {
      return "TestFinished";
    }

    @Override
    protected String getValueString() {
      return testResultSummary.toString();
    }
  }
}
