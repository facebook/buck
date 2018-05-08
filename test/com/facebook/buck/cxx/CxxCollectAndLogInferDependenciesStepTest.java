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

package com.facebook.buck.cxx;

import static com.facebook.buck.util.environment.Platform.WINDOWS;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.RuleKeyObjectSink;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.GccPreprocessor;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.ImmutableBuildTarget;
import com.facebook.buck.model.ImmutableUnflavoredBuildTarget;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.args.RuleKeyAppendableFunction;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class CxxCollectAndLogInferDependenciesStepTest {

  private static ProjectFilesystem createFakeFilesystem(String fakeRoot) {
    Path fakeRootPath = Paths.get(fakeRoot);
    Preconditions.checkArgument(fakeRootPath.isAbsolute(), "fakeRoot must be an absolute path");
    return new FakeProjectFilesystem(fakeRootPath);
  }

  private CxxInferCapture createCaptureRule(
      BuildTarget buildTarget, ProjectFilesystem filesystem, InferBuckConfig inferBuckConfig) {
    class FrameworkPathAppendableFunction
        implements RuleKeyAppendableFunction<FrameworkPath, Path> {
      @Override
      public void appendToRuleKey(RuleKeyObjectSink sink) {
        // Do nothing.
      }

      @Override
      public Path apply(FrameworkPath input) {
        return Paths.get("test", "framework", "path", input.toString());
      }
    }
    RuleKeyAppendableFunction<FrameworkPath, Path> defaultFrameworkPathSearchPathFunction =
        new FrameworkPathAppendableFunction();

    SourcePath preprocessor = FakeSourcePath.of(filesystem, "preprocessor");
    Tool preprocessorTool = new CommandTool.Builder().addInput(preprocessor).build();

    PreprocessorDelegate preprocessorDelegate =
        new PreprocessorDelegate(
            CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
            FakeSourcePath.of("whatever"),
            new GccPreprocessor(preprocessorTool),
            PreprocessorFlags.builder().build(),
            defaultFrameworkPathSearchPathFunction,
            Optional.empty(),
            /* leadingIncludePaths */ Optional.empty(),
            Optional.of(new FakeBuildRule(buildTarget.withFlavors(InternalFlavor.of("deps")))));

    return new CxxInferCapture(
        buildTarget,
        filesystem,
        ImmutableSortedSet.of(),
        CxxToolFlags.of(),
        CxxToolFlags.of(),
        FakeSourcePath.of("src.c"),
        AbstractCxxSource.Type.C,
        Optional.empty(),
        "src.o",
        preprocessorDelegate,
        inferBuckConfig);
  }

  @Test
  public void testStepWritesNoCellTokenInFileWhenCellIsAbsent()
      throws IOException, InterruptedException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    ProjectFilesystem filesystem = createFakeFilesystem("/Users/user/src");

    BuildTarget testBuildTarget =
        ImmutableBuildTarget.of(
            ImmutableUnflavoredBuildTarget.of(
                filesystem.getRootPath(), Optional.empty(), "//target", "short"),
            ImmutableSet.of(CxxInferEnhancer.InferFlavors.INFER.getFlavor()));

    InferBuckConfig inferBuckConfig = new InferBuckConfig(FakeBuckConfig.builder().build());

    CxxInferAnalyze analyzeRule =
        new CxxInferAnalyze(
            testBuildTarget, filesystem, inferBuckConfig, ImmutableSet.of(), ImmutableSet.of());

    Path outputFile = Paths.get("infer-deps.txt");
    CxxCollectAndLogInferDependenciesStep step =
        CxxCollectAndLogInferDependenciesStep.fromAnalyzeRule(analyzeRule, filesystem, outputFile);

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    int exitCode = step.execute(executionContext).getExitCode();
    assertThat(exitCode, is(StepExecutionResults.SUCCESS.getExitCode()));

    String expectedOutput =
        InferLogLine.fromBuildTarget(testBuildTarget, analyzeRule.getAbsolutePathToResultsDir())
            .toString();

    assertEquals(expectedOutput + "\n", filesystem.readFileIfItExists(outputFile).get());
  }

  @Test
  public void testStepWritesSingleCellTokenInFile() throws IOException, InterruptedException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    ProjectFilesystem filesystem = createFakeFilesystem("/Users/user/src");

    String cellName = "cellname";

    BuildTarget testBuildTarget =
        ImmutableBuildTarget.of(
            ImmutableUnflavoredBuildTarget.of(
                filesystem.getRootPath(), Optional.of(cellName), "//target", "short"),
            ImmutableSet.of(CxxInferEnhancer.InferFlavors.INFER.getFlavor()));

    InferBuckConfig inferBuckConfig = new InferBuckConfig(FakeBuckConfig.builder().build());

    CxxInferAnalyze analyzeRule =
        new CxxInferAnalyze(
            testBuildTarget, filesystem, inferBuckConfig, ImmutableSet.of(), ImmutableSet.of());

    Path outputFile = Paths.get("infer-deps.txt");
    CxxCollectAndLogInferDependenciesStep step =
        CxxCollectAndLogInferDependenciesStep.fromAnalyzeRule(analyzeRule, filesystem, outputFile);

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    int exitCode = step.execute(executionContext).getExitCode();
    assertThat(exitCode, is(StepExecutionResults.SUCCESS.getExitCode()));

    String expectedOutput =
        InferLogLine.fromBuildTarget(testBuildTarget, analyzeRule.getAbsolutePathToResultsDir())
            .toString();

    assertEquals(expectedOutput + "\n", filesystem.readFileIfItExists(outputFile).get());
  }

  @Test
  public void testStepWritesTwoCellTokensInFile() throws Exception {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    // filesystem, buildTarget and buildRuleParams for first cell (analysis)
    ProjectFilesystem filesystem1 = createFakeFilesystem("/Users/user/cell_one");
    BuildTarget buildTarget1 =
        ImmutableBuildTarget.of(
            ImmutableUnflavoredBuildTarget.of(
                filesystem1.getRootPath(), Optional.of("cell1"), "//target/in_cell_one", "short1"),
            ImmutableSet.of(CxxInferEnhancer.InferFlavors.INFER.getFlavor()));

    // filesystem, buildTarget and buildRuleParams for second cell (capture)
    ProjectFilesystem filesystem2 = createFakeFilesystem("/Users/user/cell_two");
    BuildTarget buildTarget2 =
        ImmutableBuildTarget.of(
            ImmutableUnflavoredBuildTarget.of(
                filesystem2.getRootPath(), Optional.of("cell2"), "//target/in_cell_two", "short2"),
            ImmutableSet.of(CxxInferEnhancer.INFER_CAPTURE_FLAVOR));

    InferBuckConfig inferBuckConfig = new InferBuckConfig(FakeBuckConfig.builder().build());

    CxxInferCapture captureRule = createCaptureRule(buildTarget2, filesystem2, inferBuckConfig);

    CxxInferAnalyze analyzeRule =
        new CxxInferAnalyze(
            buildTarget1,
            filesystem1,
            inferBuckConfig,
            ImmutableSet.of(captureRule),
            ImmutableSet.of());

    Path outputFile = Paths.get("infer-deps.txt");
    CxxCollectAndLogInferDependenciesStep step =
        CxxCollectAndLogInferDependenciesStep.fromAnalyzeRule(analyzeRule, filesystem1, outputFile);

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    int exitCode = step.execute(executionContext).getExitCode();
    assertThat(exitCode, is(StepExecutionResults.SUCCESS.getExitCode()));

    String expectedOutput =
        InferLogLine.fromBuildTarget(buildTarget1, analyzeRule.getAbsolutePathToResultsDir())
            + "\n"
            + InferLogLine.fromBuildTarget(buildTarget2, captureRule.getAbsolutePathToOutput());

    assertEquals(expectedOutput + "\n", filesystem1.readFileIfItExists(outputFile).get());
  }

  @Test
  public void testStepWritesOneCellTokenInFileWhenOneCellIsAbsent() throws Exception {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    // filesystem, buildTarget and buildRuleParams for first, unnamed cell (analysis)
    ProjectFilesystem filesystem1 = createFakeFilesystem("/Users/user/default_cell");
    BuildTarget buildTarget1 =
        ImmutableBuildTarget.of(
            ImmutableUnflavoredBuildTarget.of(
                filesystem1.getRootPath(), Optional.empty(), "//target/in_default_cell", "short"),
            ImmutableSet.of(CxxInferEnhancer.InferFlavors.INFER.getFlavor()));

    // filesystem, buildTarget and buildRuleParams for second cell (capture)
    ProjectFilesystem filesystem2 = createFakeFilesystem("/Users/user/cell_two");
    BuildTarget buildTarget2 =
        ImmutableBuildTarget.of(
            ImmutableUnflavoredBuildTarget.of(
                filesystem2.getRootPath(), Optional.of("cell2"), "//target/in_cell_two", "short2"),
            ImmutableSet.of(CxxInferEnhancer.INFER_CAPTURE_FLAVOR));

    InferBuckConfig inferBuckConfig = new InferBuckConfig(FakeBuckConfig.builder().build());

    CxxInferCapture captureRule = createCaptureRule(buildTarget2, filesystem2, inferBuckConfig);

    CxxInferAnalyze analyzeRule =
        new CxxInferAnalyze(
            buildTarget1,
            filesystem1,
            inferBuckConfig,
            ImmutableSet.of(captureRule),
            ImmutableSet.of());

    Path outputFile = Paths.get("infer-deps.txt");
    CxxCollectAndLogInferDependenciesStep step =
        CxxCollectAndLogInferDependenciesStep.fromAnalyzeRule(analyzeRule, filesystem1, outputFile);

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    int exitCode = step.execute(executionContext).getExitCode();
    assertThat(exitCode, is(StepExecutionResults.SUCCESS.getExitCode()));

    String expectedOutput =
        InferLogLine.fromBuildTarget(buildTarget1, analyzeRule.getAbsolutePathToResultsDir())
            + "\n"
            + InferLogLine.fromBuildTarget(buildTarget2, captureRule.getAbsolutePathToOutput());

    assertEquals(expectedOutput + "\n", filesystem1.readFileIfItExists(outputFile).get());
  }
}
