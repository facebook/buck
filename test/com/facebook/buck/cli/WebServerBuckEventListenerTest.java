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

import com.facebook.buck.httpserver.WebServerBuckEventListener;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestContext;
import com.facebook.buck.testutil.integration.TestDataHelper;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.verify;

import org.easymock.EasyMock;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class WebServerBuckEventListenerTest {
  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void hasBuckBuildStartedThenEventsCalled() throws IOException, InterruptedException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "buck_events", tmp);
    workspace.setUp();

    WebServerBuckEventListener webServerBuckEventListener =
        createMock(WebServerBuckEventListener.class);

    //Build started
    webServerBuckEventListener.buildStarted(anyObject(BuildEvent.Started.class));
    EasyMock.expectLastCall().times(1);

    //Build progress Event
    webServerBuckEventListener
        .buildProgressUpdated(anyObject(ProgressEvent.BuildProgressUpdated.class));
    EasyMock.expectLastCall().atLeastOnce();

    //Build finished
    webServerBuckEventListener.buildFinished((BuildEvent.Finished) anyObject());
    EasyMock.expectLastCall().times(1);

    //Parse started
    webServerBuckEventListener.parseStarted(anyObject(ParseEvent.Started.class));
    EasyMock.expectLastCall().times(1);

    //Parse progress Event
    webServerBuckEventListener
        .parsingProgressUpdated((ProgressEvent.ParsingProgressUpdated) anyObject());
    EasyMock.expectLastCall().atLeastOnce();

    //Parse finished
    webServerBuckEventListener.parseFinished((ParseEvent.Finished) anyObject());
    EasyMock.expectLastCall().times(1);

    //Step started
    // this project has only 1 step
    webServerBuckEventListener.stepStarted(anyObject(StepEvent.Started.class));
    EasyMock.expectLastCall().times(1);

    //Step finished
    webServerBuckEventListener.stepFinished((StepEvent.Finished) anyObject());
    EasyMock.expectLastCall().times(1);

    //Output trace
    webServerBuckEventListener.outputTrace(anyObject(BuildId.class));
    EasyMock.expectLastCall().times(1);

    EasyMock.replay(webServerBuckEventListener);

    ProjectWorkspace.ProcessResult build =
        workspace.runBuckdCommand(
            new TestContext(),
            webServerBuckEventListener,
            "build",
            "//:foo");
    build.assertSuccess();

    verify(webServerBuckEventListener);
  }
}
