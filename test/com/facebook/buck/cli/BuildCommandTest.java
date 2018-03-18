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

package com.facebook.buck.cli;

import static com.facebook.buck.rules.BuildRuleSuccessType.BUILT_LOCALLY;
import static com.facebook.buck.rules.BuildRuleSuccessType.FETCHED_FROM_CACHE;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.command.BuildExecutionResult;
import com.facebook.buck.command.BuildReport;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildResult;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class BuildCommandTest {

  private BuildExecutionResult buildExecutionResult;
  private SourcePathResolver resolver;

  @Before
  public void setUp() {
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver();
    resolver = DefaultSourcePathResolver.from(new SourcePathRuleFinder(ruleResolver));

    LinkedHashMap<BuildRule, Optional<BuildResult>> ruleToResult = new LinkedHashMap<>();

    FakeBuildRule rule1 = new FakeBuildRule(BuildTargetFactory.newInstance("//fake:rule1"));
    rule1.setOutputFile("buck-out/gen/fake/rule1.txt");
    ruleResolver.addToIndex(rule1);
    ruleToResult.put(
        rule1, Optional.of(BuildResult.success(rule1, BUILT_LOCALLY, CacheResult.miss())));

    BuildRule rule2 = new FakeBuildRule(BuildTargetFactory.newInstance("//fake:rule2"));
    BuildResult rule2Failure = BuildResult.failure(rule2, new RuntimeException("some"));
    ruleToResult.put(rule2, Optional.of(rule2Failure));
    ruleResolver.addToIndex(rule2);

    BuildRule rule3 = new FakeBuildRule(BuildTargetFactory.newInstance("//fake:rule3"));
    ruleToResult.put(
        rule3,
        Optional.of(
            BuildResult.success(
                rule3, FETCHED_FROM_CACHE, CacheResult.hit("dir", ArtifactCacheMode.dir))));
    ruleResolver.addToIndex(rule3);

    BuildRule rule4 = new FakeBuildRule(BuildTargetFactory.newInstance("//fake:rule4"));
    ruleToResult.put(rule4, Optional.empty());
    ruleResolver.addToIndex(rule4);

    buildExecutionResult =
        BuildExecutionResult.builder()
            .setResults(ruleToResult)
            .setFailures(ImmutableSet.of(rule2Failure))
            .build();
  }

  @Test
  public void testGenerateBuildReportForConsole() {
    String expectedReport =
        "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule1 "
            + "BUILT_LOCALLY "
            + MorePaths.pathWithPlatformSeparators("buck-out/gen/fake/rule1.txt")
            + "\n"
            + "\u001B[31mFAIL\u001B[0m //fake:rule2\n"
            + "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule3 FETCHED_FROM_CACHE\n"
            + "\u001B[31mFAIL\u001B[0m //fake:rule4\n"
            + "\n"
            + " ** Summary of failures encountered during the build **\n"
            + "Rule //fake:rule2 FAILED because java.lang.RuntimeException: some.";
    String observedReport =
        new BuildReport(buildExecutionResult, resolver)
            .generateForConsole(
                new Console(
                    Verbosity.STANDARD_INFORMATION,
                    new CapturingPrintStream(),
                    new CapturingPrintStream(),
                    Ansi.forceTty()));
    assertEquals(expectedReport, observedReport);
  }

  @Test
  public void testGenerateVerboseBuildReportForConsole() {
    String expectedReport =
        "OK   //fake:rule1 BUILT_LOCALLY "
            + MorePaths.pathWithPlatformSeparators("buck-out/gen/fake/rule1.txt")
            + "\n"
            + "FAIL //fake:rule2\n"
            + "OK   //fake:rule3 FETCHED_FROM_CACHE\n"
            + "FAIL //fake:rule4\n\n"
            + " ** Summary of failures encountered during the build **\n"
            + "Rule //fake:rule2 FAILED because java.lang.RuntimeException: some.";
    String observedReport =
        new BuildReport(buildExecutionResult, resolver)
            .generateForConsole(new TestConsole(Verbosity.COMMANDS));
    assertEquals(expectedReport, observedReport);
  }

  @Test
  public void testGenerateJsonBuildReport() throws IOException {
    String rule1TxtPath =
        ObjectMappers.legacyCreate()
            .valueToTree(MorePaths.pathWithPlatformSeparators("buck-out/gen/fake/rule1.txt"))
            .toString();
    String expectedReport =
        Joiner.on(System.lineSeparator())
            .join(
                "{",
                "  \"success\" : false,",
                "  \"results\" : {",
                "    \"//fake:rule1\" : {",
                "      \"success\" : true,",
                "      \"type\" : \"BUILT_LOCALLY\",",
                "      \"output\" : " + rule1TxtPath,
                "    },",
                "    \"//fake:rule2\" : {",
                "      \"success\" : false",
                "    },",
                "    \"//fake:rule3\" : {",
                "      \"success\" : true,",
                "      \"type\" : \"FETCHED_FROM_CACHE\",",
                "      \"output\" : null",
                "    },",
                "    \"//fake:rule4\" : {",
                "      \"success\" : false",
                "    }",
                "  },",
                "  \"failures\" : {",
                "    \"//fake:rule2\" : \"some\"",
                "  }",
                "}");
    String observedReport =
        new BuildReport(buildExecutionResult, resolver).generateJsonBuildReport();
    assertEquals(expectedReport, observedReport);
  }
}
