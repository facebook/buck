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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;

public class CxxGtestTestTest {

  private static final TypeReference<List<TestResultSummary>> SUMMARIES_REFERENCE =
      new TypeReference<List<TestResultSummary>>() {};

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testParseResults() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "gtest", tmp);
    workspace.setUp();

    ImmutableList<String> samples =
        ImmutableList.of(
            "big_output",
            "malformed_output",
            "malformed_results",
            "multisuite_success",
            "no_tests",
            "simple_success",
            "simple_failure",
            "simple_failure_with_output",
            "simple_disabled");

    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot());
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    CxxGtestTest test =
        new CxxGtestTest(
            new FakeBuildRuleParamsBuilder(target).setProjectFilesystem(filesystem).build(),
            ruleFinder,
            new CxxLink(
                new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:link")).build(),
                CxxPlatformUtils.DEFAULT_PLATFORM.getLd().resolve(ruleResolver),
                Paths.get("output"),
                ImmutableList.of(),
                Optional.empty(),
                Optional.empty(),
                /* cacheable */ true,
                /* thinLto */ false),
            new CommandTool.Builder().addArg(StringArg.of("")).build(),
            ImmutableMap.of(),
            Suppliers.ofInstance(ImmutableList.of()),
            ImmutableSortedSet.of(),
            ImmutableSet.of(),
            Suppliers.ofInstance(ImmutableSortedSet.of()),
            ImmutableSet.of(),
            ImmutableSet.of(),
            /* runTestSeparately */ false,
            /* testRuleTimeoutMs */ Optional.empty(),
            /* maxTestOutputSize */ 100L);

    for (String sample : samples) {
      Path exitCode = Paths.get("unused");
      Path output = workspace.resolve(Paths.get(sample)).resolve("output");
      Path results = workspace.resolve(Paths.get(sample)).resolve("results");
      Path summaries = workspace.resolve(Paths.get(sample)).resolve("summaries");
      List<TestResultSummary> expectedSummaries =
          ObjectMappers.readValue(summaries.toFile(), SUMMARIES_REFERENCE);
      ImmutableList<TestResultSummary> actualSummaries =
          test.parseResults(exitCode, output, results);
      assertEquals(sample, expectedSummaries, actualSummaries);
    }
  }
}
