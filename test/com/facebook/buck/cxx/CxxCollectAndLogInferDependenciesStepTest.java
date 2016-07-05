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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.RuleKeyAppendableFunction;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxCollectAndLogInferDependenciesStepTest {

  public static ProjectFilesystem createFakeFilesystem(String fakeRoot) {
    final Path fakeRootPath = Paths.get(fakeRoot);
    Preconditions.checkArgument(fakeRootPath.isAbsolute(), "fakeRoot must be an absolute path");

    return new FakeProjectFilesystem() {
      @Override
      public Path getRootPath() {
        return fakeRootPath;
      }

      @Override
      public Path resolve(Path relativePath) {
        return fakeRootPath.resolve(relativePath);
      }
    };
  }

  private CxxInferCapture createCaptureRule(
      BuildRuleParams buildRuleParams,
      SourcePathResolver sourcePathResolver,
      ProjectFilesystem filesystem,
      InferBuckConfig inferBuckConfig
  ) {
    RuleKeyAppendableFunction<FrameworkPath, Path> defaultFrameworkPathSearchPathFunction =
        new RuleKeyAppendableFunction<FrameworkPath, Path>() {
          @Override
          public void appendToRuleKey(RuleKeyObjectSink sink) {
            // Do nothing.
          }

          @Override
          public Path apply(FrameworkPath input) {
            return Paths.get("test", "framework", "path", input.toString());
          }
        };

    SourcePath preprocessor = new PathSourcePath(filesystem, Paths.get("preprocessor"));
    Tool preprocessorTool =
        new CommandTool.Builder()
            .addInput(preprocessor)
            .build();

    PreprocessorDelegate preprocessorDelegate = new PreprocessorDelegate(
        sourcePathResolver,
        CxxPlatformUtils.DEFAULT_DEBUG_PATH_SANITIZER,
        CxxPlatformUtils.DEFAULT_CONFIG.getHeaderVerification(),
        Paths.get("whatever"),
        new DefaultPreprocessor(preprocessorTool),
        PreprocessorFlags.builder().build(),
        defaultFrameworkPathSearchPathFunction,
        ImmutableList.<CxxHeaders>of());

    return new CxxInferCapture(
        buildRuleParams,
        sourcePathResolver,
        CxxToolFlags.of(),
        CxxToolFlags.of(),
        new FakeSourcePath("src.c"),
        AbstractCxxSource.Type.C,
        Paths.get("src.o"),
        preprocessorDelegate,
        inferBuckConfig,
        CxxPlatformUtils.DEFAULT_DEBUG_PATH_SANITIZER);
  }

  @Test
  public void testStepWritesNoCellTokenInFileWhenCellIsAbsent()
      throws IOException, InterruptedException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    ProjectFilesystem filesystem = createFakeFilesystem("/Users/user/src");

    BuildTarget testBuildTarget = BuildTarget
        .builder()
        .setUnflavoredBuildTarget(
            UnflavoredBuildTarget.of(
                filesystem.getRootPath(),
                Optional.<String>absent(),
                "//target",
                "short"))
        .addFlavors(CxxInferEnhancer.InferFlavors.INFER.get())
        .build();

    BuildRuleParams testBuildRuleParams = new FakeBuildRuleParamsBuilder(testBuildTarget)
        .setProjectFilesystem(filesystem)
        .build();

    BuildRuleResolver testBuildRuleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver testSourcePathResolver = new SourcePathResolver(testBuildRuleResolver);

    InferBuckConfig inferBuckConfig = new InferBuckConfig(FakeBuckConfig.builder().build());

    CxxInferCaptureAndAggregatingRules<CxxInferAnalyze> captureAndAggregatingRules =
        new CxxInferCaptureAndAggregatingRules<>(
            ImmutableSet.<CxxInferCapture>of(),
            ImmutableSet.<CxxInferAnalyze>of());

    CxxInferAnalyze analyzeRule = new CxxInferAnalyze(
        testBuildRuleParams,
        testSourcePathResolver,
        inferBuckConfig,
        captureAndAggregatingRules);

    Path outputFile = Paths.get("infer-deps.txt");
    CxxCollectAndLogInferDependenciesStep step =
        CxxCollectAndLogInferDependenciesStep.fromAnalyzeRule(
            analyzeRule,
            filesystem,
            outputFile);

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    int exitCode = step.execute(executionContext).getExitCode();
    assertThat(exitCode, is(StepExecutionResult.SUCCESS.getExitCode()));

    String expectedOutput =
        new InferLogLine(testBuildTarget, analyzeRule.getResultsDir()).toString();

    assertEquals(expectedOutput + "\n", filesystem.readFileIfItExists(outputFile).get());
  }

  @Test
  public void testStepWritesSingleCellTokenInFile() throws IOException, InterruptedException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    ProjectFilesystem filesystem = createFakeFilesystem("/Users/user/src");

    String cellName = "cellname";

    BuildTarget testBuildTarget = BuildTarget
        .builder()
        .setUnflavoredBuildTarget(
            UnflavoredBuildTarget.of(
                filesystem.getRootPath(),
                Optional.of(cellName),
                "//target",
                "short"))
        .addFlavors(CxxInferEnhancer.InferFlavors.INFER.get())
        .build();

    BuildRuleParams testBuildRuleParams = new FakeBuildRuleParamsBuilder(testBuildTarget)
        .setProjectFilesystem(filesystem)
        .build();

    BuildRuleResolver testBuildRuleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver testSourcePathResolver = new SourcePathResolver(testBuildRuleResolver);

    InferBuckConfig inferBuckConfig = new InferBuckConfig(FakeBuckConfig.builder().build());

    CxxInferCaptureAndAggregatingRules<CxxInferAnalyze> captureAndAggregatingRules =
        new CxxInferCaptureAndAggregatingRules<>(
            ImmutableSet.<CxxInferCapture>of(),
            ImmutableSet.<CxxInferAnalyze>of());

    CxxInferAnalyze analyzeRule = new CxxInferAnalyze(
        testBuildRuleParams,
        testSourcePathResolver,
        inferBuckConfig,
        captureAndAggregatingRules);

    Path outputFile = Paths.get("infer-deps.txt");
    CxxCollectAndLogInferDependenciesStep step =
        CxxCollectAndLogInferDependenciesStep.fromAnalyzeRule(
            analyzeRule,
            filesystem,
            outputFile);

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    int exitCode = step.execute(executionContext).getExitCode();
    assertThat(exitCode, is(StepExecutionResult.SUCCESS.getExitCode()));

    String expectedOutput =
        new InferLogLine(testBuildTarget, analyzeRule.getResultsDir()).toString();

    assertEquals(expectedOutput + "\n", filesystem.readFileIfItExists(outputFile).get());
  }

  @Test
  public void testStepWritesTwoCellTokensInFile() throws IOException, InterruptedException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    // filesystem, buildTarget and buildRuleParams for first cell (analysis)
    ProjectFilesystem filesystem1 = createFakeFilesystem("/Users/user/cell_one");
    BuildTarget buildTarget1 = BuildTarget
        .builder()
        .setUnflavoredBuildTarget(
            UnflavoredBuildTarget.of(
                filesystem1.getRootPath(),
                Optional.of("cell1"),
                "//target/in_cell_one",
                "short1"))
        .addFlavors(CxxInferEnhancer.InferFlavors.INFER.get())
        .build();
    BuildRuleParams buildRuleParams1 = new FakeBuildRuleParamsBuilder(buildTarget1)
        .setProjectFilesystem(filesystem1)
        .build();

    // filesystem, buildTarget and buildRuleParams for second cell (capture)
    ProjectFilesystem filesystem2 = createFakeFilesystem("/Users/user/cell_two");
    BuildTarget buildTarget2 = BuildTarget
        .builder()
        .setUnflavoredBuildTarget(
            UnflavoredBuildTarget.of(
                filesystem2.getRootPath(),
                Optional.of("cell2"),
                "//target/in_cell_two",
                "short2"))
        .addFlavors(CxxInferEnhancer.InferFlavors.INFER_CAPTURE.get())
        .build();
    BuildRuleParams buildRuleParams2 = new FakeBuildRuleParamsBuilder(buildTarget2)
        .setProjectFilesystem(filesystem2)
        .build();


    BuildRuleResolver testBuildRuleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver testSourcePathResolver = new SourcePathResolver(testBuildRuleResolver);

    InferBuckConfig inferBuckConfig = new InferBuckConfig(FakeBuckConfig.builder().build());

    CxxInferCapture captureRule =
        createCaptureRule(buildRuleParams2, testSourcePathResolver, filesystem2, inferBuckConfig);

    CxxInferCaptureAndAggregatingRules<CxxInferAnalyze> captureAndAggregatingRules =
        new CxxInferCaptureAndAggregatingRules<>(
            ImmutableSet.of(captureRule),
            ImmutableSet.<CxxInferAnalyze>of());

    CxxInferAnalyze analyzeRule = new CxxInferAnalyze(
        buildRuleParams1,
        testSourcePathResolver,
        inferBuckConfig,
        captureAndAggregatingRules);

    Path outputFile = Paths.get("infer-deps.txt");
    CxxCollectAndLogInferDependenciesStep step =
        CxxCollectAndLogInferDependenciesStep.fromAnalyzeRule(
            analyzeRule,
            filesystem1,
            outputFile);

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    int exitCode = step.execute(executionContext).getExitCode();
    assertThat(exitCode, is(StepExecutionResult.SUCCESS.getExitCode()));

    String expectedOutput =
        new InferLogLine(buildTarget1, analyzeRule.getResultsDir()).toString() + "\n" +
        new InferLogLine(buildTarget2, captureRule.getPathToOutput()).toString();

    assertEquals(expectedOutput + "\n", filesystem1.readFileIfItExists(outputFile).get());
  }

  @Test
  public void testStepWritesOneCellTokenInFileWhenOneCellIsAbsent()
      throws IOException, InterruptedException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));

    // filesystem, buildTarget and buildRuleParams for first, unnamed cell (analysis)
    ProjectFilesystem filesystem1 = createFakeFilesystem("/Users/user/default_cell");
    BuildTarget buildTarget1 = BuildTarget
        .builder()
        .setUnflavoredBuildTarget(
            UnflavoredBuildTarget.of(
                filesystem1.getRootPath(),
                Optional.<String>absent(),
                "//target/in_default_cell",
                "short"))
        .addFlavors(CxxInferEnhancer.InferFlavors.INFER.get())
        .build();
    BuildRuleParams buildRuleParams1 = new FakeBuildRuleParamsBuilder(buildTarget1)
        .setProjectFilesystem(filesystem1)
        .build();

    // filesystem, buildTarget and buildRuleParams for second cell (capture)
    ProjectFilesystem filesystem2 = createFakeFilesystem("/Users/user/cell_two");
    BuildTarget buildTarget2 = BuildTarget
        .builder()
        .setUnflavoredBuildTarget(
            UnflavoredBuildTarget.of(
                filesystem2.getRootPath(),
                Optional.of("cell2"),
                "//target/in_cell_two",
                "short2"))
        .addFlavors(CxxInferEnhancer.InferFlavors.INFER_CAPTURE.get())
        .build();
    BuildRuleParams buildRuleParams2 = new FakeBuildRuleParamsBuilder(buildTarget2)
        .setProjectFilesystem(filesystem2)
        .build();


    BuildRuleResolver testBuildRuleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver testSourcePathResolver = new SourcePathResolver(testBuildRuleResolver);

    InferBuckConfig inferBuckConfig = new InferBuckConfig(FakeBuckConfig.builder().build());

    CxxInferCapture captureRule =
        createCaptureRule(buildRuleParams2, testSourcePathResolver, filesystem2, inferBuckConfig);

    CxxInferCaptureAndAggregatingRules<CxxInferAnalyze> captureAndAggregatingRules =
        new CxxInferCaptureAndAggregatingRules<>(
            ImmutableSet.of(captureRule),
            ImmutableSet.<CxxInferAnalyze>of());

    CxxInferAnalyze analyzeRule = new CxxInferAnalyze(
        buildRuleParams1,
        testSourcePathResolver,
        inferBuckConfig,
        captureAndAggregatingRules);

    Path outputFile = Paths.get("infer-deps.txt");
    CxxCollectAndLogInferDependenciesStep step =
        CxxCollectAndLogInferDependenciesStep.fromAnalyzeRule(
            analyzeRule,
            filesystem1,
            outputFile);

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    int exitCode = step.execute(executionContext).getExitCode();
    assertThat(exitCode, is(StepExecutionResult.SUCCESS.getExitCode()));

    String expectedOutput =
        new InferLogLine(buildTarget1, analyzeRule.getResultsDir()).toString() + "\n" +
        new InferLogLine(buildTarget2, captureRule.getPathToOutput()).toString();

    assertEquals(expectedOutput + "\n", filesystem1.readFileIfItExists(outputFile).get());
  }
}
