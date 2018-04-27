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

package com.facebook.buck.apple;

import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation of {@link XctestOutputParsing.XctestEventCallback} that collects {@code xctest}
 * events and converts them to {@link TestCaseSummary} objects, reporting progress to a {@code
 * TestRule.TestReportingCallback}.
 */
class TestCaseSummariesBuildingXctestEventHandler
    implements XctestOutputParsing.XctestEventCallback {
  private final TestRule.TestReportingCallback testReportingCallback;
  private final ImmutableListMultimap.Builder<String, TestResultSummary> testResultSummariesBuilder;
  private final ImmutableList.Builder<TestCaseSummary> testCaseSummariesBuilder;
  private AtomicBoolean testsDidEnd;

  public TestCaseSummariesBuildingXctestEventHandler(
      TestRule.TestReportingCallback testReportingCallback) {
    this.testReportingCallback = testReportingCallback;
    this.testResultSummariesBuilder = ImmutableListMultimap.builder();
    this.testCaseSummariesBuilder = ImmutableList.builder();
    this.testsDidEnd = new AtomicBoolean(false);
  }

  @Override
  public void handleBeginXctestEvent(XctestOutputParsing.BeginXctestEvent event) {
    testReportingCallback.testsDidBegin();
  }

  @Override
  public void handleEndXctestEvent(XctestOutputParsing.EndXctestEvent event) {
    boolean testsDidEndChangedToTrue = testsDidEnd.compareAndSet(false, true);
    Preconditions.checkState(
        testsDidEndChangedToTrue, "handleEndXctestEvent() should not be called twice");
    for (Map.Entry<String, Collection<TestResultSummary>> testCaseSummary :
        testResultSummariesBuilder.build().asMap().entrySet()) {
      testCaseSummariesBuilder.add(
          new TestCaseSummary(
              testCaseSummary.getKey(), ImmutableList.copyOf(testCaseSummary.getValue())));
    }
    testReportingCallback.testsDidEnd(testCaseSummariesBuilder.build());
  }

  @Override
  public void handleBeginTestSuiteEvent(XctestOutputParsing.BeginTestSuiteEvent event) {}

  @Override
  public void handleEndTestSuiteEvent(XctestOutputParsing.EndTestSuiteEvent event) {}

  @Override
  public void handleBeginTestCaseEvent(XctestOutputParsing.BeginTestCaseEvent event) {
    testReportingCallback.testDidBegin(
        Optional.ofNullable(event.className).orElse(Preconditions.checkNotNull(event.test)),
        Optional.ofNullable(event.methodName).orElse(Preconditions.checkNotNull(event.test)));
  }

  @Override
  public void handleEndTestCaseEvent(XctestOutputParsing.EndTestCaseEvent event) {
    TestResultSummary testResultSummary =
        XctestOutputParsing.testResultSummaryForEndTestCaseEvent(event);
    testResultSummariesBuilder.put(
        Optional.ofNullable(event.className).orElse(Preconditions.checkNotNull(event.test)),
        testResultSummary);
    testReportingCallback.testDidEnd(testResultSummary);
  }

  public ImmutableList<TestCaseSummary> getTestCaseSummaries() {
    Preconditions.checkState(
        testsDidEnd.get(), "Call this method only after testsDidEnd() callback is invoked.");
    return testCaseSummariesBuilder.build();
  }
}
