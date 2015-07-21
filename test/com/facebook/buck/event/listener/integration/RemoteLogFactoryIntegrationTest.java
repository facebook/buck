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

package com.facebook.buck.event.listener.integration;

import static org.junit.Assert.assertThat;

import com.facebook.buck.json.HasJsonField;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import com.google.common.io.CharStreams;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class RemoteLogFactoryIntegrationTest {

  private static class PostRequestsHandler extends AbstractHandler {

    private final ListMultimap<String, String> responsePathToContentsMap =
        ArrayListMultimap.create();

    @Override
    public void handle(
        String target,
        Request request,
        HttpServletRequest httpServletRequest,
        HttpServletResponse httpServletResponse) throws IOException, ServletException {
      if (!HttpMethod.POST.is(request.getMethod())) {
        return;
      }
      String contents = CharStreams.toString(new InputStreamReader(request.getInputStream()));
      responsePathToContentsMap.put(request.getUri().toString(), contents);
      request.setHandled(true);
    }

    public Set<String> getPutRequestsPaths() {
      return responsePathToContentsMap.keySet();
    }

    public List<String> getContents(String path) throws IllegalArgumentException {
      return responsePathToContentsMap.get(path);
    }
  }
  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  private ObjectMapper objectMapper = new ObjectMapper();
  private HttpdForTests httpd;
  private PostRequestsHandler putRequestsHandler;

  @Before
  public void startHttpd() throws Exception {
    httpd = new HttpdForTests();
    putRequestsHandler = new PostRequestsHandler();
    httpd.addHandler(putRequestsHandler);
    httpd.start();
  }

  @After
  public void shutdownHttpd() throws Exception {
    httpd.close();
  }

  @Test
  public void testResultsUpload() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "remote_log", temporaryFolder);
    // We won't verify the workspace as there are no "expected" files.
    workspace.setUp();
    workspace.replaceFileContents(".buckconfig", "<remote_log_url>", httpd.getUri("").toString());

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", "//test:test");
    result.assertSuccess("buck project should exit cleanly");

    assertThat(putRequestsHandler.getPutRequestsPaths(), Matchers.contains("/"));

    List<String> contents = putRequestsHandler.getContents("/");
    ImmutableList.Builder<JsonNode> eventsBuilder = ImmutableList.builder();
    for (String batch : contents) {
      JsonNode jsonNode = objectMapper.readTree(batch);
      assertThat(jsonNode, Matchers.hasProperty("array", Matchers.is(true)));
      eventsBuilder.addAll(jsonNode);
    }
    ImmutableList<JsonNode> events = eventsBuilder.build();
    assertThat(events,
        Matchers.hasItem(
            new HasJsonField(
                objectMapper,
                "type",
                Matchers.equalTo(objectMapper.valueToTree("CommandStarted")))));
  }
}
