package com.facebook.buck.event.listener;

import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.List;
import java.util.concurrent.TimeUnit;

class TestMethodResultMapBuilder implements ResultBuilder {

  @Override
  public List<TestRun.Result> build(
      ImmutableSet<String> contacts,
      TestCaseSummary testCaseSummary) {
    ImmutableList.Builder<TestRun.Result> builder = new ImmutableList.Builder<>();
    for (TestResultSummary testResultSummary : testCaseSummary.getTestResults()) {
      TestRun.Status status = TestRun.Status.convertResultType(testResultSummary.getType());
      String name = String.format(
          "%s#%s",
          testCaseSummary.getTestCaseName(),
          testResultSummary.getTestName());
      builder.add(new TestRun.Result(
          /* name */ name,
          /* contacts */ contacts,
          /* durationSecs */ testResultSummary.getTime() / 1000,
          /* status */ status,
          /* endedTime */ TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()),
          /* summary */ status.toString().toUpperCase(),
          /* details */ buildDetails(testResultSummary)));
    }
    return builder.build();
  }

  private String buildDetails(TestResultSummary testResultSummary) {
    ImmutableList.Builder<String> lines = new ImmutableList.Builder<>();
    TestResultFormatter.reportResultSummary(lines, testResultSummary);
    return Joiner.on("\n").join(lines.build());
  }
}
