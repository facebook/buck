/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.httpserver;

import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.IndividualTestEvent;
import com.facebook.buck.rules.TestRunEvent;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.Subscribe;


/**
 * {@link BuckEventListener} that is responsible for reporting events of interest to the
 * {@link StreamingWebSocketServlet}. This class passes high-level objects to the servlet, and the
 * servlet takes responsibility for serializing the objects as JSON down to the client.
 */
public class WebServerBuckEventListener implements BuckEventListener {
  private final StreamingWebSocketServlet streamingWebSocketServlet;

  WebServerBuckEventListener(WebServer webServer) {
    Preconditions.checkNotNull(webServer);
    this.streamingWebSocketServlet = webServer.getStreamingWebSocketServlet();
  }

  @Override
  public void outputTrace(String buildId) {}

  @Subscribe
  public void parseStarted(ParseEvent.Started started) {
    streamingWebSocketServlet.tellClients(started);
  }

  @Subscribe
  public void parseFinished(ParseEvent.Finished finished) {
    streamingWebSocketServlet.tellClients(finished);
  }

  @Subscribe
  public void buildStarted(BuildEvent.Started started) {
    streamingWebSocketServlet.tellClients(started);
  }

  @Subscribe
  public void buildFinished(BuildEvent.Finished finished) {
    streamingWebSocketServlet.tellClients(finished);
  }

  @Subscribe
  public void buildRuleStarted(BuildRuleEvent.Started started) {
    streamingWebSocketServlet.tellClients(started);
  }

  @Subscribe
  public void buildRuleFinished(BuildRuleEvent.Finished finished) {
    streamingWebSocketServlet.tellClients(finished);
  }

  @Subscribe
  public void testRunStarted(TestRunEvent.Started event) {
    streamingWebSocketServlet.tellClients(event);
  }

  @Subscribe
  public void testRunCompleted(TestRunEvent.Finished event) {
    streamingWebSocketServlet.tellClients(event);
  }

  @Subscribe
  public void testAwaitingResults(IndividualTestEvent.Started event) {
    streamingWebSocketServlet.tellClients(event);
  }

  @Subscribe
  public void testResultsAvailable(IndividualTestEvent.Finished event) {
    streamingWebSocketServlet.tellClients(event);
  }

}
