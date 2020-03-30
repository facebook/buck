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

package com.facebook.buck.intellij.ideabuck.ws.buckevents;

import com.facebook.buck.event.external.events.BuckEventExternalInterface;
import com.facebook.buck.event.external.events.CompilerErrorEventExternalInterface;
import com.facebook.buck.event.external.events.ConsoleEventExternalInterface;
import com.facebook.buck.event.external.events.IndividualTestEventFinishedExternalInterface;
import com.facebook.buck.event.external.events.InstallFinishedEventExternalInterface;
import com.facebook.buck.event.external.events.ProgressEventInterface;
import com.facebook.buck.event.external.events.StepEventExternalInterface;
import com.facebook.buck.event.external.events.TestRunFinishedEventInterface;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckBuildFinishedHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckBuildProgressHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckBuildStartedHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckCompilerErrorHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckConsoleEventHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckEventHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckIndividualTestAwaitingResultsHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckInstallFinishedHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckInstallStartedHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckParseFinishedHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckParseStartedHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckParsingProgressHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckProjectGenerationFinishedHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckProjectGenerationProgressHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckProjectGenerationStartedHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckResultsAvailableHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckStepFinishedHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckStepStartedHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckTestRunCompleteHandler;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.handlers.BuckTestRunStartedHandler;
import java.util.HashMap;

public class BuckEventsAdapter extends HashMap<String, BuckEventHandler> {
  public BuckEventsAdapter() {
    super();
    put(BuckEventExternalInterface.BUILD_FINISHED, new BuckBuildFinishedHandler());
    put(ProgressEventInterface.BUILD_PROGRESS_UPDATED, new BuckBuildProgressHandler());
    put(BuckEventExternalInterface.BUILD_STARTED, new BuckBuildStartedHandler());
    put(CompilerErrorEventExternalInterface.COMPILER_ERROR_EVENT, new BuckCompilerErrorHandler());
    put(ConsoleEventExternalInterface.CONSOLE_EVENT, new BuckConsoleEventHandler());
    put(
        IndividualTestEventFinishedExternalInterface.INDIVIDUAL_TEST_AWAITING_RESULTS,
        new BuckIndividualTestAwaitingResultsHandler());
    put(InstallFinishedEventExternalInterface.INSTALL_FINISHED, new BuckInstallFinishedHandler());
    put(BuckEventExternalInterface.INSTALL_STARTED, new BuckInstallStartedHandler());
    put(BuckEventExternalInterface.PARSE_FINISHED, new BuckParseFinishedHandler());
    put(BuckEventExternalInterface.PARSE_STARTED, new BuckParseStartedHandler());
    put(ProgressEventInterface.PARSING_PROGRESS_UPDATED, new BuckParsingProgressHandler());
    put(
        BuckEventExternalInterface.PROJECT_GENERATION_FINISHED,
        new BuckProjectGenerationFinishedHandler());
    put(
        ProgressEventInterface.PROJECT_GENERATION_PROGRESS_UPDATED,
        new BuckProjectGenerationProgressHandler());
    put(
        BuckEventExternalInterface.PROJECT_GENERATION_STARTED,
        new BuckProjectGenerationStartedHandler());
    put(
        IndividualTestEventFinishedExternalInterface.RESULTS_AVAILABLE,
        new BuckResultsAvailableHandler());
    put(TestRunFinishedEventInterface.RUN_COMPLETE, new BuckTestRunCompleteHandler());
    put(StepEventExternalInterface.STEP_FINISHED, new BuckStepFinishedHandler());
    put(StepEventExternalInterface.STEP_STARTED, new BuckStepStartedHandler());
    put(BuckEventExternalInterface.TEST_RUN_STARTED, new BuckTestRunStartedHandler());

    // For events that we don't handle, but don't want to log about
    put(BuckEventExternalInterface.CACHE_RATE_STATS_UPDATE_EVENT, BuckEventHandler.IGNORE);
  }
}
