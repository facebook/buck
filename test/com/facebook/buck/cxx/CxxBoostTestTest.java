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

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
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

public class CxxBoostTestTest {

  private static final TypeReference<List<TestResultSummary>> SUMMARIES_REFERENCE =
      new TypeReference<List<TestResultSummary>>() {};

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testParseResults() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "boost_test", tmp);
    workspace.setUp();

    ImmutableList<String> samples =
        ImmutableList.of("simple_success", "simple_failure", "simple_failure_with_output");

    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    BuildTarget linkTarget = BuildTargetFactory.newInstance("//:link");
    CxxBoostTest test =
        new CxxBoostTest(
            target,
            projectFilesystem,
            TestBuildRuleParams.create(),
            new CxxLink(
                linkTarget,
                new FakeProjectFilesystem(),
                ruleFinder,
                TestCellPathResolver.get(projectFilesystem),
                CxxPlatformUtils.DEFAULT_PLATFORM.getLd().resolve(ruleResolver),
                Paths.get("output"),
                ImmutableMap.of(),
                ImmutableList.of(),
                Optional.empty(),
                Optional.empty(),
                /* cacheable */ true,
                /* thinLto */ false),
            new CommandTool.Builder().addArg(StringArg.of("")).build(),
            ImmutableMap.of(),
            ImmutableList.of(),
            ImmutableSortedSet.of(),
            ImmutableSet.of(),
            unused -> ImmutableSortedSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(),
            /* runTestSeparately */ false,
            /* testRuleTimeoutMs */ Optional.empty());

    for (String sample : samples) {
      Path exitCode = Paths.get("unused");
      Path output = workspace.resolve(Paths.get(sample)).resolve("output");
      Path results = workspace.resolve(Paths.get(sample)).resolve("results");
      Path summaries = workspace.resolve(Paths.get(sample)).resolve("summaries");
      List<TestResultSummary> expectedSummaries =
          ObjectMappers.readValue(summaries, SUMMARIES_REFERENCE);
      ImmutableList<TestResultSummary> actualSummaries =
          test.parseResults(exitCode, output, results);
      assertEquals(sample, expectedSummaries, actualSummaries);
    }
  }
}
