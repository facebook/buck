/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.verify;

import com.facebook.buck.event.CompilerErrorEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ProgressEvent;
import com.facebook.buck.event.ProjectGenerationEvent;
import com.facebook.buck.httpserver.WebServerBuckEventListener;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.IndividualTestEvent;
import com.facebook.buck.rules.TestRunEvent;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.easymock.EasyMock;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

/**
 * This tests capture the expectations of the intellij buck plugin. Upon modification, please inform
 * the team that is currently maintaining the intellij buck plugin.
 */
public class WebServerBuckEventListenerTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  @Ignore
  public void hasBuckBuildStartedThenEventsCalled() throws IOException, InterruptedException {
    final ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "buck_events", tmp);
    workspace.setUp();

    WebServerBuckEventListener webServerBuckEventListener =
        createMock(WebServerBuckEventListener.class);

    //Build started
    webServerBuckEventListener.buildStarted(anyObject(BuildEvent.Started.class));
    EasyMock.expectLastCall().times(1);

    //Build progress Event
    webServerBuckEventListener.buildProgressUpdated(
        anyObject(ProgressEvent.BuildProgressUpdated.class));
    EasyMock.expectLastCall().atLeastOnce();

    //Build finished
    webServerBuckEventListener.buildFinished((BuildEvent.Finished) anyObject());
    EasyMock.expectLastCall().times(1);

    //Parse started
    webServerBuckEventListener.parseStarted(anyObject(ParseEvent.Started.class));
    EasyMock.expectLastCall().times(1);

    //Parse progress Event
    webServerBuckEventListener.parsingProgressUpdated(
        (ProgressEvent.ParsingProgressUpdated) anyObject());
    EasyMock.expectLastCall().atLeastOnce();

    //Parse finished
    webServerBuckEventListener.parseFinished((ParseEvent.Finished) anyObject());
    EasyMock.expectLastCall().times(1);

    //Output trace
    webServerBuckEventListener.outputTrace(anyObject(BuildId.class));
    EasyMock.expectLastCall().times(1);

    EasyMock.replay(webServerBuckEventListener);

    ProjectWorkspace.ProcessResult build =
        workspace.runBuckdCommand(new TestContext(), "build", "//:foo");
    build.assertSuccess();

    verify(webServerBuckEventListener);
  }

  @Test
  @Ignore
  public void hasBuckTestStartedThenEventsCalled() throws IOException, InterruptedException {
    final ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "buck_events/test", tmp);
    workspace.setUp();

    WebServerBuckEventListener webServerBuckEventListener =
        createMock(WebServerBuckEventListener.class);

    //Build started
    webServerBuckEventListener.buildStarted(anyObject(BuildEvent.Started.class));
    EasyMock.expectLastCall().times(1);

    //Build progress Event
    webServerBuckEventListener.buildProgressUpdated(
        anyObject(ProgressEvent.BuildProgressUpdated.class));
    EasyMock.expectLastCall().atLeastOnce();

    //Build finished
    webServerBuckEventListener.buildFinished(anyObject(BuildEvent.Finished.class));
    EasyMock.expectLastCall().times(1);

    //Parse started
    webServerBuckEventListener.parseStarted(anyObject(ParseEvent.Started.class));
    EasyMock.expectLastCall().times(1);

    //Parse progress Event
    webServerBuckEventListener.parsingProgressUpdated(
        anyObject(ProgressEvent.ParsingProgressUpdated.class));
    EasyMock.expectLastCall().atLeastOnce();

    //Parse finished
    webServerBuckEventListener.parseFinished(anyObject(ParseEvent.Finished.class));
    EasyMock.expectLastCall().times(1);

    //Individual test started
    //This target has only 1 test
    webServerBuckEventListener.testAwaitingResults(anyObject(IndividualTestEvent.Started.class));
    EasyMock.expectLastCall().times(1);

    //Individual test finished
    webServerBuckEventListener.testResultsAvailable(anyObject(IndividualTestEvent.Finished.class));
    EasyMock.expectLastCall().times(1);

    //Test started
    webServerBuckEventListener.testRunStarted(anyObject(TestRunEvent.Started.class));
    EasyMock.expectLastCall().times(1);

    //Test finished
    webServerBuckEventListener.testRunCompleted(anyObject(TestRunEvent.Finished.class));
    EasyMock.expectLastCall().times(1);

    //Output trace
    webServerBuckEventListener.outputTrace(anyObject(BuildId.class));
    EasyMock.expectLastCall().times(1);

    EasyMock.replay(webServerBuckEventListener);

    ProjectWorkspace.ProcessResult build =
        workspace.runBuckdCommand(new TestContext(), "test", "//:simple_test");
    build.assertSuccess();

    verify(webServerBuckEventListener);
  }

  @Test
  @Ignore
  public void hasBuckCompilerErrorOccurredThenEventsCalled()
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "buck_events/compiler_error", tmp);
    workspace.setUp();

    WebServerBuckEventListener webServerBuckEventListener =
        createMock(WebServerBuckEventListener.class);

    //Build started
    webServerBuckEventListener.buildStarted(anyObject(BuildEvent.Started.class));
    EasyMock.expectLastCall().times(1);

    //Build progress Event
    webServerBuckEventListener.buildProgressUpdated(
        anyObject(ProgressEvent.BuildProgressUpdated.class));
    EasyMock.expectLastCall().atLeastOnce();

    //Build finished
    webServerBuckEventListener.buildFinished(anyObject(BuildEvent.Finished.class));
    EasyMock.expectLastCall().times(1);

    //Parse started
    webServerBuckEventListener.parseStarted(anyObject(ParseEvent.Started.class));
    EasyMock.expectLastCall().times(1);

    //Parse progress Event
    webServerBuckEventListener.parsingProgressUpdated(
        anyObject(ProgressEvent.ParsingProgressUpdated.class));
    EasyMock.expectLastCall().atLeastOnce();

    //Parse finished
    webServerBuckEventListener.parseFinished(anyObject(ParseEvent.Finished.class));
    EasyMock.expectLastCall().times(1);

    //Compiler error
    webServerBuckEventListener.compilerErrorEvent(anyObject(CompilerErrorEvent.class));
    EasyMock.expectLastCall().times(1);

    //Console event
    webServerBuckEventListener.consoleEvent(anyObject(ConsoleEvent.class));
    EasyMock.expectLastCall().times(1);

    //Output trace
    webServerBuckEventListener.outputTrace(anyObject(BuildId.class));
    EasyMock.expectLastCall().times(1);

    EasyMock.replay(webServerBuckEventListener);

    ProjectWorkspace.ProcessResult build =
        workspace.runBuckdCommand(new TestContext(), "build", "//:broken");
    build.assertFailure();

    verify(webServerBuckEventListener);
  }

  @Test
  @Ignore
  public void hasBuckProjectGenerationStartedThenEventsCalled()
      throws IOException, InterruptedException {
    final ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "buck_events", tmp);
    workspace.setUp();

    WebServerBuckEventListener webServerBuckEventListener =
        createMock(WebServerBuckEventListener.class);

    //Parse started
    webServerBuckEventListener.parseStarted(anyObject(ParseEvent.Started.class));
    EasyMock.expectLastCall().times(1);

    //Parse progress Event
    webServerBuckEventListener.parsingProgressUpdated(
        anyObject(ProgressEvent.ParsingProgressUpdated.class));
    EasyMock.expectLastCall().atLeastOnce();

    //Parse finished
    webServerBuckEventListener.parseFinished(anyObject(ParseEvent.Finished.class));
    EasyMock.expectLastCall().times(1);

    //Project generation started
    webServerBuckEventListener.projectGenerationStarted(
        anyObject(ProjectGenerationEvent.Started.class));
    EasyMock.expectLastCall().times(1);

    //Project generation finished
    webServerBuckEventListener.projectGenerationFinished(
        anyObject(ProjectGenerationEvent.Finished.class));
    EasyMock.expectLastCall().times(1);

    //Output trace
    webServerBuckEventListener.outputTrace(anyObject(BuildId.class));
    EasyMock.expectLastCall().times(1);

    EasyMock.replay(webServerBuckEventListener);

    ProjectWorkspace.ProcessResult build =
        workspace.runBuckdCommand(new TestContext(), "project", "//:foo");
    build.assertSuccess();

    verify(webServerBuckEventListener);
  }
}
