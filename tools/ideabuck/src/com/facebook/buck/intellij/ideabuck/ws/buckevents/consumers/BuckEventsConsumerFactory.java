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

package com.facebook.buck.intellij.ideabuck.ws.buckevents.consumers;

import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBus;

public class BuckEventsConsumerFactory {

  private MessageBus mBus;

  public BuckEventsConsumerFactory(Project project) {
    mBus = project.getMessageBus();
  }

  public RulesParsingStartConsumer getRulesParsingStartConsumer() {
    return mBus.syncPublisher(RulesParsingStartConsumer.BUCK_PARSE_RULE_START);
  }

  public RulesParsingEndConsumer getRulesParsingEndConsumer() {
    return mBus.syncPublisher(RulesParsingEndConsumer.BUCK_PARSE_RULE_END);
  }

  public RulesParsingProgressUpdateConsumer getRulesParsingProgressUpdateConsumer() {
    return mBus.syncPublisher(RulesParsingProgressUpdateConsumer.BUCK_PARSE_PROGRESS_UPDATE);
  }

  public TestRunStartedConsumer getTestRunStartedConsumer() {
    return mBus.syncPublisher(TestRunStartedConsumer.BUCK_TEST_RUN_STARTED);
  }

  public TestRunCompleteConsumer getTestRunCompleteConsumer() {
    return mBus.syncPublisher(TestRunCompleteConsumer.BUCK_TEST_RUN_COMPLETE);
  }

  public TestResultsAvailableConsumer getTestResultsAvailableConsumer() {
    return mBus.syncPublisher(TestResultsAvailableConsumer.BUCK_TEST_RESULTS_AVAILABLE);
  }

  public BuckBuildStartConsumer getBuildStartConsumer() {
    return mBus.syncPublisher(BuckBuildStartConsumer.BUCK_BUILD_START);
  }

  public BuckBuildEndConsumer getBuildEndConsumer() {
    return mBus.syncPublisher(BuckBuildEndConsumer.BUCK_BUILD_END);
  }

  public BuckBuildProgressUpdateConsumer getBuckBuildProgressUpdateConsumer() {
    return mBus.syncPublisher(BuckBuildProgressUpdateConsumer.BUCK_BUILD_PROGRESS_UPDATE);
  }

  public CompilerErrorConsumer getCompilerErrorConsumer() {
    return mBus.syncPublisher(CompilerErrorConsumer.COMPILER_ERROR_CONSUMER);
  }

  public BuckConsoleEventConsumer getConsoleEventConsumer() {
    return mBus.syncPublisher(BuckConsoleEventConsumer.BUCK_CONSOLE_EVENT);
  }

  public BuckProjectGenerationFinishedConsumer getBuckProjectGenerationFinishedConsumer() {
    return mBus.syncPublisher(
        BuckProjectGenerationFinishedConsumer.PROJECT_GENERATION_FINISHED_CONSUMER);
  }

  public BuckProjectGenerationProgressConsumer getBuckProjectGenerationProgressConsumer() {
    return mBus.syncPublisher(
        BuckProjectGenerationProgressConsumer.PROJECT_GENERATION_PROGRESS_CONSUMER);
  }

  public BuckProjectGenerationStartedConsumer getBuckProjectGenerationStartedConsumer() {
    return mBus.syncPublisher(
        BuckProjectGenerationStartedConsumer.PROJECT_GENERATION_STARTED_CONSUMER);
  }

  public BuckInstallFinishedConsumer getInstallFinishedConsumer() {
    return mBus.syncPublisher(BuckInstallFinishedConsumer.INSTALL_FINISHED_CONSUMER);
  }
}
