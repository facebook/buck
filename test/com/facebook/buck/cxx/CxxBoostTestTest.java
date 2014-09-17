/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.Label;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProjectFilesystem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class CxxBoostTestTest {

  private static final ObjectMapper mapper = new ObjectMapper();
  private static final TypeReference<List<TestResultSummary>> SUMMARIES_REFERENCE =
      new TypeReference<List<TestResultSummary>>() {};

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testParseResults() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "boost_test", tmp);
    workspace.setUp();

    ImmutableList<String> samples =
        ImmutableList.of(
            "simple_success",
            "simple_failure",
            "simple_failure_with_output");

    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    CxxBoostTest test = new CxxBoostTest(
        BuildRuleParamsFactory.createTrivialBuildRuleParams(target),
        Paths.get(""),
        ImmutableSet.<Label>of(),
        ImmutableSet.<String>of(),
        ImmutableSet.<BuildRule>of());

    ExecutionContext context =
        TestExecutionContext.newBuilder()
            .setProjectFilesystem(
                new ProjectFilesystem(tmp.getRoot().toPath()))
            .build();

    for (String sample : samples) {
      Path exitCode = Paths.get("unused");
      Path output = workspace.resolve(Paths.get(sample)).resolve("output");
      Path results = workspace.resolve(Paths.get(sample)).resolve("results");
      Path summaries = workspace.resolve(Paths.get(sample)).resolve("summaries");
      List<TestResultSummary> expectedSummaries =
          mapper.readValue(summaries.toFile(), SUMMARIES_REFERENCE);
      ImmutableList<TestResultSummary> actualSummaries =
          test.parseResults(context, exitCode, output, results);
      assertEquals(sample, expectedSummaries, actualSummaries);
    }

  }

}
