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

package com.facebook.buck.command;

import static com.facebook.buck.core.build.engine.BuildRuleSuccessType.BUILT_LOCALLY;
import static com.facebook.buck.core.build.engine.BuildRuleSuccessType.FETCHED_FROM_CACHE;
import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.impl.PathReferenceRule;
import com.facebook.buck.core.rules.impl.PathReferenceRuleWithMultipleOutputs;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuildReportTest {
  @Rule public final ExpectedException exception = ExpectedException.none();

  private Build.BuildExecutionResult buildExecutionResult;
  private SourcePathResolverAdapter resolver;
  private Cells cells;
  private ActionGraphBuilder graphBuilder;
  private Map<BuildRule, Optional<BuildResult>> ruleToResult;
  private RuntimeException runtimeException;

  @Before
  public void setUp() {
    graphBuilder = new TestActionGraphBuilder();
    resolver = graphBuilder.getSourcePathResolver();
    cells = new TestCellBuilder().build();
    ruleToResult = new LinkedHashMap<>();

    FakeBuildRule rule1 = new FakeBuildRule(BuildTargetFactory.newInstance("//fake:rule1"));
    rule1.setOutputFile("buck-out/gen/fake/rule1.txt");
    graphBuilder.addToIndex(rule1);
    ruleToResult.put(
        rule1, Optional.of(BuildResult.success(rule1, BUILT_LOCALLY, CacheResult.miss())));

    BuildRule rule2 = new FakeBuildRule(BuildTargetFactory.newInstance("//fake:rule2"));
    runtimeException = new RuntimeException("some");
    BuildResult rule2Failure = BuildResult.failure(rule2, runtimeException);
    ruleToResult.put(rule2, Optional.of(rule2Failure));
    graphBuilder.addToIndex(rule2);

    BuildRule rule3 = new FakeBuildRule(BuildTargetFactory.newInstance("//fake:rule3"));
    ruleToResult.put(
        rule3,
        Optional.of(
            BuildResult.success(
                rule3, FETCHED_FROM_CACHE, CacheResult.hit("dir", ArtifactCacheMode.dir))));
    graphBuilder.addToIndex(rule3);

    BuildRule rule4 = new FakeBuildRule(BuildTargetFactory.newInstance("//fake:rule4"));
    ruleToResult.put(rule4, Optional.empty());
    graphBuilder.addToIndex(rule4);

    BuildRule rule5 =
        new PathReferenceRuleWithMultipleOutputs(
            BuildTargetFactory.newInstance("//fake:rule5"),
            new FakeProjectFilesystem(),
            Paths.get("default_output"),
            ImmutableMap.of(
                OutputLabel.of("named_1"), ImmutableSet.of(Paths.get("named_output_1"))));
    graphBuilder.addToIndex(rule5);
    ruleToResult.put(
        rule5, Optional.of(BuildResult.success(rule1, BUILT_LOCALLY, CacheResult.miss())));

    BuildRule rule6 =
        new PathReferenceRuleWithMultipleOutputs(
            BuildTargetFactory.newInstance("//fake:rule6"),
            new FakeProjectFilesystem(),
            Paths.get("default_single_output"),
            ImmutableMap.of(
                OutputLabel.defaultLabel(),
                ImmutableSet.of(Paths.get("default_output1"), Paths.get("default_output_2")),
                OutputLabel.of("named_1"),
                ImmutableSet.of(Paths.get("named_output_1")),
                OutputLabel.of("named_2"),
                ImmutableSet.of(Paths.get("named_output_2"), Paths.get("named_output_22"))));
    graphBuilder.addToIndex(rule6);
    ruleToResult.put(
        rule6, Optional.of(BuildResult.success(rule1, BUILT_LOCALLY, CacheResult.miss())));

    buildExecutionResult =
        ImmutableBuildExecutionResult.of(ruleToResult, ImmutableSet.of(rule2Failure));
  }

  @Test
  public void testGenerateBuildReportForConsole() {
    String expectedReport =
        linesToText(
            "(?s)"
                + Pattern.quote(
                    "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule1 "
                        + "BUILT_LOCALLY "
                        + MorePaths.pathWithPlatformSeparators("buck-out/gen/fake/rule1.txt")),
            Pattern.quote("\u001B[31mFAIL\u001B[0m //fake:rule2"),
            Pattern.quote(
                "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule3 FETCHED_FROM_CACHE"),
            Pattern.quote("\u001B[31mFAIL\u001B[0m //fake:rule4"),
            Pattern.quote(
                "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule5 "
                    + "BUILT_LOCALLY "
                    + MorePaths.pathWithPlatformSeparators("default_output")),
            Pattern.quote(
                "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule5[named_1] "
                    + "BUILT_LOCALLY "
                    + MorePaths.pathWithPlatformSeparators("named_output_1")),
            Pattern.quote(
                "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule6 "
                    + "BUILT_LOCALLY "
                    + MorePaths.pathWithPlatformSeparators("default_output1")),
            Pattern.quote(
                "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule6 "
                    + "BUILT_LOCALLY "
                    + MorePaths.pathWithPlatformSeparators("default_output_2")),
            Pattern.quote(
                "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule6[named_1] "
                    + "BUILT_LOCALLY "
                    + MorePaths.pathWithPlatformSeparators("named_output_1")),
            Pattern.quote(
                "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule6[named_2] "
                    + "BUILT_LOCALLY "
                    + MorePaths.pathWithPlatformSeparators("named_output_2")),
            Pattern.quote(
                "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule6[named_2] "
                    + "BUILT_LOCALLY "
                    + MorePaths.pathWithPlatformSeparators("named_output_22")),
            "",
            " \\*\\* Summary of failures encountered during the build \\*\\*",
            "Rule //fake:rule2 FAILED because java.lang.RuntimeException: some",
            "\tat .*");
    String observedReport =
        new BuildReport(buildExecutionResult, resolver, cells.getRootCell())
            .generateForConsole(
                new Console(
                    Verbosity.STANDARD_INFORMATION,
                    new CapturingPrintStream(),
                    new CapturingPrintStream(),
                    Ansi.forceTty()));
    assertThat(observedReport, Matchers.matchesPattern(expectedReport));
  }

  @Test
  public void testGenerateVerboseBuildReportForConsole() {
    String expectedReport =
        linesToText(
            "(?s)OK   //fake:rule1 BUILT_LOCALLY "
                + Pattern.quote(
                    MorePaths.pathWithPlatformSeparators("buck-out/gen/fake/rule1.txt")),
            "FAIL //fake:rule2",
            "OK   //fake:rule3 FETCHED_FROM_CACHE",
            "FAIL //fake:rule4",
            "OK   //fake:rule5 BUILT_LOCALLY default_output",
            "OK   //fake:rule5\\[named_1\\] BUILT_LOCALLY named_output_1",
            "OK   //fake:rule6 BUILT_LOCALLY default_output1",
            "OK   //fake:rule6 BUILT_LOCALLY default_output_2",
            "OK   //fake:rule6\\[named_1\\] BUILT_LOCALLY named_output_1",
            "OK   //fake:rule6\\[named_2\\] BUILT_LOCALLY named_output_2",
            "OK   //fake:rule6\\[named_2\\] BUILT_LOCALLY named_output_22",
            "",
            " \\*\\* Summary of failures encountered during the build \\*\\*",
            "Rule //fake:rule2 FAILED because java.lang.RuntimeException: some",
            "\tat .*");
    String observedReport =
        new BuildReport(buildExecutionResult, resolver, cells.getRootCell())
            .generateForConsole(new TestConsole(Verbosity.COMMANDS));
    assertThat(observedReport, Matchers.matchesPattern(expectedReport));
  }

  @Test
  public void testGenerateJsonBuildReport() throws IOException {
    String rule1TxtPath =
        ObjectMappers.legacyCreate()
            .valueToTree(MorePaths.pathWithPlatformSeparators("buck-out/gen/fake/rule1.txt"))
            .toString();
    String expectedReport =
        String.join(
            System.lineSeparator(),
            "{",
            "  \"success\" : false,",
            "  \"results\" : {",
            "    \"//fake:rule1\" : {",
            "      \"success\" : \"SUCCESS\",",
            "      \"type\" : \"BUILT_LOCALLY\",",
            "      \"output\" : " + rule1TxtPath + ",",
            "      \"outputs\" : {",
            "        \"DEFAULT\" : [ " + rule1TxtPath + " ]",
            "      }",
            "    },",
            "    \"//fake:rule2\" : {",
            "      \"success\" : \"FAIL\"",
            "    },",
            "    \"//fake:rule3\" : {",
            "      \"success\" : \"SUCCESS\",",
            "      \"type\" : \"FETCHED_FROM_CACHE\"",
            "    },",
            "    \"//fake:rule4\" : {",
            "      \"success\" : \"UNKNOWN\"",
            "    },",
            "    \"//fake:rule5\" : {",
            "      \"success\" : \"SUCCESS\",",
            "      \"type\" : \"BUILT_LOCALLY\",",
            "      \"output\" : \"default_output\",",
            "      \"outputs\" : {",
            "        \"DEFAULT\" : [ \"default_output\" ],",
            "        \"named_1\" : [ \"named_output_1\" ]",
            "      }",
            "    },",
            "    \"//fake:rule6\" : {",
            "      \"success\" : \"SUCCESS\",",
            "      \"type\" : \"BUILT_LOCALLY\",",
            "      \"outputs\" : {",
            "        \"DEFAULT\" : [ \"default_output1\", \"default_output_2\" ],",
            "        \"named_1\" : [ \"named_output_1\" ],",
            "        \"named_2\" : [ \"named_output_2\", \"named_output_22\" ]",
            "      }",
            "    }",
            "  },",
            "  \"failures\" : {",
            "    \"//fake:rule2\" : \""
                + Throwables.getStackTraceAsString(runtimeException)
                    .replace("\r\n", "\n")
                    .replace("\t", "\\t")
                    .replace("\n", "\\n")
                + "\"",
            "  }",
            "}");
    String observedReport =
        new BuildReport(buildExecutionResult, resolver, cells.getRootCell())
            .generateJsonBuildReport();
    assertEquals(expectedReport.replace("\\r\\n", "\\n"), observedReport.replace("\\r\\n", "\\n"));
  }

  @Test
  public void removeOutput() throws IOException {
    String expectedReport =
        String.join(
            System.lineSeparator(),
            "{",
            "  \"success\" : true,",
            "  \"results\" : {",
            "    \"//fake:rule\" : {",
            "      \"success\" : \"SUCCESS\",",
            "      \"type\" : \"BUILT_LOCALLY\",",
            "      \"outputs\" : {",
            "        \"DEFAULT\" : [ \"foobar\" ]",
            "      }",
            "    }",
            "  },",
            "  \"failures\" : { }",
            "}");

    BuildRule rule =
        new PathReferenceRule(
            BuildTargetFactory.newInstance("//fake:rule"),
            new FakeProjectFilesystem(),
            Paths.get("foobar"));
    ImmutableMap<BuildRule, Optional<BuildResult>> ruleResultWithOutput =
        ImmutableMap.of(
            rule, Optional.of(BuildResult.success(rule, BUILT_LOCALLY, CacheResult.miss())));
    Build.BuildExecutionResult buildExecutionResult =
        ImmutableBuildExecutionResult.of(ruleResultWithOutput, ImmutableSet.of());
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setFilesystem(new FakeProjectFilesystem())
            .setSections(ImmutableMap.of("build_report", ImmutableMap.of("remove_output", "true")))
            .build();
    Cells fakeCells = new TestCellBuilder().setBuckConfig(buckConfig).build();

    String observedReport =
        new BuildReport(buildExecutionResult, resolver, fakeCells.getRootCell())
            .generateJsonBuildReport();

    assertEquals(expectedReport.replace("\\r\\n", "\\n"), observedReport.replace("\\r\\n", "\\n"));
  }
}
