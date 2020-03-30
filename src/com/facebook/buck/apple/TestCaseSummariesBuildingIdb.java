/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation of {@link IdbOutputParsing.IdbResultCallback} that collects {@code xctool} events
 * and converts them to {@link TestCaseSummary} objects, reporting progress to a {@code
 * TestRule.TestReportingCallback}.
 */
public class TestCaseSummariesBuildingIdb implements IdbOutputParsing.IdbResultCallback {

  private final TestRule.TestReportingCallback testReportingCallback;
  private final ImmutableListMultimap.Builder<String, TestResultSummary> testResultSummariesBuilder;
  private final ImmutableList.Builder<TestCaseSummary> testCaseSummariesBuilder;
  private AtomicBoolean testsDidEnd;

  public TestCaseSummariesBuildingIdb(TestRule.TestReportingCallback testReportingCallback) {
    this.testReportingCallback = testReportingCallback;
    this.testResultSummariesBuilder = ImmutableListMultimap.builder();
    this.testCaseSummariesBuilder = ImmutableList.builder();
    this.testsDidEnd = new AtomicBoolean(false);
  }

  @Override
  public void handleTestResult(ImmutableIdbTestResult result) {
    TestResultSummary testResultSummary = IdbOutputParsing.testResultSummaryForTestResult(result);
    testResultSummariesBuilder.put(
        Objects.requireNonNull(result.getClassName()), testResultSummary);
    testReportingCallback.testDidBegin(result.getClassName(), result.getMethodName());
    testReportingCallback.testDidEnd(testResultSummary);
  }

  @Override
  public void handleEndOfTests() {
    boolean testsDidEndChangedToTrue = testsDidEnd.compareAndSet(false, true);
    Preconditions.checkState(
        testsDidEndChangedToTrue, "handleEndOcunitEvent() should not be called twice");
    for (Map.Entry<String, Collection<TestResultSummary>> testCaseSummary :
        testResultSummariesBuilder.build().asMap().entrySet()) {
      testCaseSummariesBuilder.add(
          new TestCaseSummary(
              testCaseSummary.getKey(), ImmutableList.copyOf(testCaseSummary.getValue())));
    }
    testReportingCallback.testsDidEnd(testCaseSummariesBuilder.build());
  }

  public ImmutableList<TestCaseSummary> getTestCaseSummaries() {
    Preconditions.checkState(
        testsDidEnd.get(), "Call this method only after testsDidEnd() callback is invoked.");
    return testCaseSummariesBuilder.build();
  }
}
