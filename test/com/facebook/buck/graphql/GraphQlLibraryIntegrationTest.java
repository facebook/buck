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

package com.facebook.buck.graphql;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class GraphQlLibraryIntegrationTest {

  @Rule
  public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  private static final ObjectMapper objectMapper = ObjectMappers.newDefaultInstance();

  private static JsonNode parseJSON(String content) throws IOException {
    return objectMapper.readTree(objectMapper.getFactory().createParser(content));
  }

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "graphql_library_test",
        tmpFolder);
    workspace.setUp();
  }

  @Test
  public void testBuckQueryForGraphQlLibrarySrcs() throws Exception {
    ProjectWorkspace.ProcessResult result = workspace
        .runBuckCommand(
            "query",
            "kind(\'graphql_library\', \'//...\')",
            "--output-attributes",
            "srcs");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("srcs-query-results.json")))));
  }

  @Test
  public void testBuckQueryForGraphQlLibraryDeps() throws Exception {
    ProjectWorkspace.ProcessResult result = workspace
        .runBuckCommand(
            "query",
            "--json",
            "deps(//:AppGraphQl)");
    result.assertSuccess();
    assertThat(
        parseJSON(result.getStdout()),
        is(equalTo(parseJSON(workspace.getFileContents("deps-query-results.json")))));
  }
}
