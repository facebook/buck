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

package com.facebook.buck.intellij.ideabuck.configurations;

import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.BuckBuildEndConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.BuckBuildStartConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.CompilerErrorConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.TestResultsAvailableConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.TestRunCompleteConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers.TestRunStartedConsumer;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.parts.TestCaseSummary;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.parts.TestResults;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.parts.TestResultsSummary;
import com.facebook.buck.test.result.type.ResultType;
import com.google.common.collect.ImmutableSet;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.sm.runner.GeneralTestEventsProcessor;
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter;
import com.intellij.execution.testframework.sm.runner.events.TestFailedEvent;
import com.intellij.execution.testframework.sm.runner.events.TestFinishedEvent;
import com.intellij.execution.testframework.sm.runner.events.TestOutputEvent;
import com.intellij.execution.testframework.sm.runner.events.TestStartedEvent;
import com.intellij.execution.testframework.sm.runner.events.TestSuiteFinishedEvent;
import com.intellij.execution.testframework.sm.runner.events.TestSuiteStartedEvent;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.text.DateFormatUtil;
import java.util.Date;
import org.jetbrains.annotations.NotNull;

public class BuckToGeneralTestEventsConverter extends OutputToGeneralTestEventsConverter
    implements TestRunStartedConsumer,
        TestRunCompleteConsumer,
        TestResultsAvailableConsumer,
        BuckBuildStartConsumer,
        BuckBuildEndConsumer,
        CompilerErrorConsumer {

  @NotNull private final ProcessHandler myHandler;
  @NotNull private final Project mProject;
  MessageBusConnection mConnection;

  public BuckToGeneralTestEventsConverter(
      @NotNull String testFrameworkName,
      @NotNull TestConsoleProperties consoleProperties,
      @NotNull ProcessHandler handler,
      @NotNull Project project) {
    super(testFrameworkName, consoleProperties);
    myHandler = handler;
    mProject = project;
  }

  @Override
  public void onStartTesting() {
    mConnection = mProject.getMessageBus().connect();
    mConnection.subscribe(TestResultsAvailableConsumer.BUCK_TEST_RESULTS_AVAILABLE, this);
    mConnection.subscribe(TestRunCompleteConsumer.BUCK_TEST_RUN_COMPLETE, this);
    mConnection.subscribe(TestRunStartedConsumer.BUCK_TEST_RUN_STARTED, this);
    mConnection.subscribe(BuckBuildStartConsumer.BUCK_BUILD_START, this);
    mConnection.subscribe(BuckBuildEndConsumer.BUCK_BUILD_END, this);
    mConnection.subscribe(CompilerErrorConsumer.COMPILER_ERROR_CONSUMER, this);
    myHandler.addProcessListener(
        new ProcessAdapter() {
          @Override
          public void processTerminated(ProcessEvent event) {
            mConnection.disconnect();
          }
        });
  }

  @Override
  public void consumeTestRunStarted(long timestamp) {
    final GeneralTestEventsProcessor processor = getProcessor();
    if (processor == null) {
      return;
    }
    processor.onUncapturedOutput(
        "Test run started at " + DateFormatUtil.formatTimeWithSeconds(new Date(timestamp)) + "\n",
        ProcessOutputTypes.STDOUT);
  }

  @Override
  public void consumeTestRunComplete(long timestamp) {
    mConnection.disconnect();
    myHandler.detachProcess();
    final GeneralTestEventsProcessor processor = getProcessor();
    if (processor == null) {
      return;
    }
    processor.onFinishTesting();
  }

  @Override
  public void consumeTestResultsAvailable(long timestamp, TestResults testResults) {
    final GeneralTestEventsProcessor processor = getProcessor();
    if (processor == null) {
      return;
    }
    for (TestCaseSummary suite : testResults.getTestCases()) {
      if (suite.getTestResults().isEmpty()) {
        continue;
      }
      processor.onSuiteStarted(new TestSuiteStartedEvent(suite.getTestCaseName(), null));

      for (TestResultsSummary test : suite.getTestResults()) {
        final String testName = test.getTestName();
        String locationUrl = String.format("java:test://%s.%s", test.getTestCaseName(), testName);
        processor.onTestStarted(new TestStartedEvent(testName, locationUrl));
        final String stdOut = test.getStdOut();
        if (stdOut != null) {
          processor.onTestOutput(new TestOutputEvent(testName, stdOut, true));
        }
        final String stdErr = test.getStdErr();
        if (stdErr != null) {
          processor.onTestOutput(new TestOutputEvent(testName, stdErr, false));
        }
        if (test.getType() == ResultType.FAILURE) {
          processor.onTestFailure(
              new TestFailedEvent(testName, "", test.getStacktrace(), true, null, null));
        }
        processor.onTestFinished(new TestFinishedEvent(testName, test.getTime()));
      }
      processor.onSuiteFinished(new TestSuiteFinishedEvent(suite.getTestCaseName()));
    }
  }

  @Override
  public void consumeBuildEnd(long timestamp) {
    final GeneralTestEventsProcessor processor = getProcessor();
    if (processor == null) {
      return;
    }
    processor.onUncapturedOutput(
        "Build finished at " + DateFormatUtil.formatTimeWithSeconds(new Date(timestamp)) + "\n",
        ProcessOutputTypes.STDOUT);
  }

  @Override
  public void consumeBuildStart(long timestamp) {
    final GeneralTestEventsProcessor processor = getProcessor();
    if (processor == null) {
      return;
    }
    processor.onUncapturedOutput(
        "Build started at " + DateFormatUtil.formatTimeWithSeconds(new Date(timestamp)) + "\n",
        ProcessOutputTypes.STDOUT);
  }

  @Override
  public void consumeCompilerError(
      String target, long timestamp, String error, ImmutableSet<String> suggestions) {
    final GeneralTestEventsProcessor processor = getProcessor();
    if (processor != null) {
      processor.onUncapturedOutput(
          "Build failed at " + DateFormatUtil.formatTimeWithSeconds(new Date(timestamp)) + "\n",
          ProcessOutputTypes.STDOUT);
    }
    mConnection.disconnect();
    myHandler.detachProcess();
  }
}
