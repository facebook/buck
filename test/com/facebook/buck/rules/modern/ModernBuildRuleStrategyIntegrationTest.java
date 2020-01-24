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

package com.facebook.buck.rules.modern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeFalse;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.remoteexecution.config.RemoteExecutionType;
import com.facebook.buck.remoteexecution.grpc.server.GrpcServer;
import com.facebook.buck.rules.modern.config.ModernBuildRuleBuildStrategy;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ModernBuildRuleStrategyIntegrationTest {
  private static final String SIMPLE_TARGET = "//:simple";
  private static final String FAILING_TARGET = "//:failing";
  private static final String FAILING_STEP_TARGET = "//:failing_step";
  private static final String LARGE_DYNAMIC_TARGET = "//:large_dynamic";
  private static final String HUGE_DYNAMIC_TARGET = "//:huge_dynamic";
  private static final String DUPLICATE_OUTPUT_FILES_TARGET = "//:duplicate_output_files";
  private static final String DUPLICATE_OUTPUT_DIRS_TARGET = "//:duplicate_output_dirs";
  private static final String CHECK_SERIALIZATION_TARGET = "//:check_serialization";

  @Parameterized.Parameters(name = "{0}.{1}")
  public static Collection<Object[]> data() {
    return ImmutableList.<Object[]>builder()
        .add(new Object[] {ModernBuildRuleBuildStrategy.NONE, RemoteExecutionType.NONE})
        .add(
            new Object[] {ModernBuildRuleBuildStrategy.DEBUG_RECONSTRUCT, RemoteExecutionType.NONE})
        .add(
            new Object[] {ModernBuildRuleBuildStrategy.DEBUG_PASSTHROUGH, RemoteExecutionType.NONE})
        // Remote execution strategies.
        .add(new Object[] {ModernBuildRuleBuildStrategy.REMOTE, RemoteExecutionType.GRPC})
        .add(new Object[] {ModernBuildRuleBuildStrategy.HYBRID_LOCAL, RemoteExecutionType.GRPC})
        // TODO(shivanker): We don't have a dummy implementation for Thrift in this repository.
        // Probably add this in the future to be able to have unit tests.
        // .add(new Object[] {ModernBuildRuleBuildStrategy.REMOTE, RemoteExecutionType.THRIFT})
        .add(
            new Object[] {
              ModernBuildRuleBuildStrategy.REMOTE, RemoteExecutionType.DEBUG_GRPC_IN_PROCESS
            })
        .add(
            new Object[] {
              ModernBuildRuleBuildStrategy.REMOTE, RemoteExecutionType.DEBUG_GRPC_LOCAL
            })
        .build();
  }

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private final ModernBuildRuleBuildStrategy strategy;
  private final RemoteExecutionType executionType;
  private Optional<GrpcServer> server = Optional.empty();
  private ProjectWorkspace workspace;
  private ProjectFilesystem filesystem;

  @RuleArg
  interface AbstractTouchOutputDescriptionArg extends HasDeclaredDeps, BuildRuleArg {
    String getOut();
  }

  private static class TouchOutputDescription
      implements DescriptionWithTargetGraph<TouchOutputDescriptionArg> {
    @Override
    public Class<TouchOutputDescriptionArg> getConstructorArgType() {
      return TouchOutputDescriptionArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph creationContext,
        BuildTarget buildTarget,
        BuildRuleParams params,
        TouchOutputDescriptionArg args) {
      return new TouchOutput(
          buildTarget,
          creationContext.getProjectFilesystem(),
          creationContext.getActionGraphBuilder(),
          args.getOut());
    }
  }

  @RuleArg
  interface AbstractCheckSerializationArg extends BuildRuleArg {}

  private static class CheckSerializationDescription
      implements DescriptionWithTargetGraph<CheckSerializationArg> {
    @Override
    public Class<CheckSerializationArg> getConstructorArgType() {
      return CheckSerializationArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph creationContext,
        BuildTarget buildTarget,
        BuildRuleParams params,
        CheckSerializationArg args) {
      return new CheckSerialization(
          buildTarget,
          creationContext.getProjectFilesystem(),
          creationContext.getActionGraphBuilder());
    }
  }

  private static class CheckSerialization extends ModernBuildRule<CheckSerialization>
      implements Buildable {
    @AddToRuleKey private final String target;
    @AddToRuleKey private final String type;
    @AddToRuleKey private final OutputPath output;

    protected CheckSerialization(
        BuildTarget buildTarget, ProjectFilesystem filesystem, SourcePathRuleFinder finder) {
      super(buildTarget, filesystem, finder, CheckSerialization.class);
      this.target = getBuildTarget().toString();
      this.type = getType();
      this.output = new OutputPath("output");
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      Preconditions.checkState(
          filesystem.getRootPath().equals(getProjectFilesystem().getRootPath()));
      Preconditions.checkState(target.equals(getBuildTarget().toString()));
      Preconditions.checkState(type.equals(getType()));
      return ImmutableList.of(new TouchStep(filesystem, outputPathResolver.resolvePath(output)));
    }
  }

  @RuleArg
  interface AbstractLargeDynamicsArg extends HasDeclaredDeps, BuildRuleArg {
    Optional<BuildTarget> getFirstRef();

    Optional<BuildTarget> getSecondRef();

    String getValue();
  }

  private static class LargeDynamicsDescription
      implements DescriptionWithTargetGraph<LargeDynamicsArg> {
    @Override
    public Class<LargeDynamicsArg> getConstructorArgType() {
      return LargeDynamicsArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        LargeDynamicsArg args) {
      ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
      Optional<LargeDynamics> firstRef =
          args.getFirstRef().map(graphBuilder::requireRule).map(LargeDynamics.class::cast);
      Optional<LargeDynamics> secondRef =
          args.getSecondRef().map(graphBuilder::requireRule).map(LargeDynamics.class::cast);

      return new LargeDynamics(
          buildTarget,
          context.getProjectFilesystem(),
          graphBuilder,
          firstRef,
          secondRef,
          args.getValue().charAt(0));
    }
  }

  @RuleArg
  interface AbstractFailingRuleArg extends BuildRuleArg {
    boolean getStepFailure();
  }

  private static class FailingRuleDescription
      implements DescriptionWithTargetGraph<FailingRuleArg> {
    @Override
    public Class<FailingRuleArg> getConstructorArgType() {
      return FailingRuleArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        FailingRuleArg args) {
      return new FailingRule(
          buildTarget,
          context.getProjectFilesystem(),
          context.getActionGraphBuilder(),
          args.getStepFailure());
    }
  }

  private static class FailingRule extends ModernBuildRule<FailingRule> implements Buildable {
    private static final String FAILING_STEP_MESSAGE = "FailingStep";
    private static final String FAILING_RULE_MESSAGE = "FailingRule";
    private static final String STEP_CONSOLE_EVENT_MESSAGE = "Step Console event!";
    private static final String RULE_CONSOLE_EVENT_MESSAGE = "Rule Console event!";
    private static final String RULE_CONSOLE_EVENT_INFO_MESSAGE = "INFO level Rule Console event!";

    @AddToRuleKey private final OutputPath output = new OutputPath("some.file");
    @AddToRuleKey private final boolean stepFailure;

    FailingRule(
        BuildTarget buildTarget,
        ProjectFilesystem filesystem,
        SourcePathRuleFinder finder,
        boolean stepFailure) {
      super(buildTarget, filesystem, finder, FailingRule.class);
      this.stepFailure = stepFailure;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      buildContext.getEventBus().post(ConsoleEvent.info(RULE_CONSOLE_EVENT_INFO_MESSAGE));
      if (stepFailure) {
        return ImmutableList.of(
            new AbstractExecutionStep("throwing_step") {
              @Override
              public StepExecutionResult execute(ExecutionContext context) {
                context.getBuckEventBus().post(ConsoleEvent.severe(STEP_CONSOLE_EVENT_MESSAGE));
                throw new HumanReadableException(FAILING_STEP_MESSAGE);
              }
            });
      }
      buildContext.getEventBus().post(ConsoleEvent.warning(RULE_CONSOLE_EVENT_MESSAGE));
      throw new HumanReadableException(FAILING_RULE_MESSAGE);
    }
  }

  @Before
  public void setUp() throws IOException {
    // MBR strategies use a ContentAddressedStorageClient that doesn't work correctly on Windows.
    assumeFalse(Platform.detect().equals(Platform.WINDOWS));
    workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "strategies", tmpFolder);
    workspace.setKnownRuleTypesFactoryFactory(
        (executor,
            pluginManager,
            sandboxExecutionStrategyFactory,
            knownConfigurationDescriptions) ->
            cell ->
                KnownNativeRuleTypes.of(
                    ImmutableList.of(
                        new TouchOutputDescription(),
                        new LargeDynamicsDescription(),
                        new FailingRuleDescription(),
                        new DuplicateOutputsDescription(),
                        new CheckSerializationDescription()),
                    knownConfigurationDescriptions,
                    ImmutableList.of()));
    workspace.setUp();
    workspace.addBuckConfigLocalOption("modern_build_rule", "strategy", strategy.toString());
    workspace.addBuckConfigLocalOption("remoteexecution", "type", executionType.toString());

    int remotePort = -1;

    if (executionType == RemoteExecutionType.GRPC) {
      // TODO(cjhopman): newer versions of grpc can find us a port.
      for (int i = 0; i < 100; i++) {
        if (server.isPresent()) {
          break;
        }
        try (ServerSocket socket = new ServerSocket(0)) {
          remotePort = socket.getLocalPort();
        }
        try {
          server = Optional.of(new GrpcServer(remotePort));
        } catch (Exception e) { // NOPMD
        }
      }
      Preconditions.checkState(server.isPresent());
    }

    workspace.addBuckConfigLocalOption(
        "remoteexecution", "remote_port", Integer.toString(remotePort));
    workspace.addBuckConfigLocalOption("remoteexecution", "insecure", "yes");
    workspace.addBuckConfigLocalOption("remoteexecution", "cas_port", Integer.toString(remotePort));
    workspace.addBuckConfigLocalOption("remoteexecution", "cas_insecure", "yes");

    filesystem = workspace.getProjectFileSystem();

    if (strategy == ModernBuildRuleBuildStrategy.HYBRID_LOCAL) {
      workspace.addBuckConfigLocalOption(
          "modern_build_rule#remote", "strategy", ModernBuildRuleBuildStrategy.REMOTE.toString());
      workspace.addBuckConfigLocalOption("modern_build_rule", "local_jobs", "0");
      workspace.addBuckConfigLocalOption("modern_build_rule", "delegate_jobs", "1");
      workspace.addBuckConfigLocalOption("modern_build_rule", "delegate", "remote");
    }
  }

  @After
  public void tearDown() throws Exception {
    if (server.isPresent()) {
      server.get().close();
    }
  }

  public ModernBuildRuleStrategyIntegrationTest(
      ModernBuildRuleBuildStrategy strategy, RemoteExecutionType executionType) {
    this.strategy = strategy;
    this.executionType = executionType;
  }

  @Test
  public void testBuildSimpleRule() throws Exception {
    ProcessResult result = workspace.runBuckBuild(SIMPLE_TARGET);
    result.assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(SIMPLE_TARGET);
    assertEquals(
        "",
        workspace.getFileContents(
            new DefaultOutputPathResolver(filesystem, BuildTargetFactory.newInstance(SIMPLE_TARGET))
                .resolvePath(new OutputPath("some.path"))));
  }

  @Test
  public void testAbstractBuildRuleFieldSerialization() {
    ProcessResult result = workspace.runBuckBuild(CHECK_SERIALIZATION_TARGET);
    result.assertSuccess();
  }

  @RuleArg
  interface AbstractDuplicateOutputsArg extends BuildRuleArg {
    boolean getOutputsAreDirectories();
  }

  private static class DuplicateOutputsDescription
      implements DescriptionWithTargetGraph<DuplicateOutputsArg> {
    @Override
    public Class<DuplicateOutputsArg> getConstructorArgType() {
      return DuplicateOutputsArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        DuplicateOutputsArg args) {
      return new DuplicateOutputsRule(
          buildTarget,
          context.getProjectFilesystem(),
          context.getActionGraphBuilder(),
          args.getOutputsAreDirectories());
    }
  }

  private static class DuplicateOutputsRule extends ModernBuildRule<DuplicateOutputsRule>
      implements Buildable {
    @AddToRuleKey final OutputPath output1;
    @AddToRuleKey final OutputPath output2;
    @AddToRuleKey final boolean outputsAreDirectories;

    DuplicateOutputsRule(
        BuildTarget buildTarget,
        ProjectFilesystem filesystem,
        SourcePathRuleFinder finder,
        boolean outputsAreDirectories) {
      super(buildTarget, filesystem, finder, DuplicateOutputsRule.class);
      this.outputsAreDirectories = outputsAreDirectories;
      this.output1 = new OutputPath("output1");
      this.output2 = new OutputPath("output2");
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      return ImmutableList.of(
          new AbstractExecutionStep("blah") {
            public void writeOutput(OutputPath path) throws IOException {
              String data = "data";
              Path resolved = outputPathResolver.resolvePath(path);
              if (outputsAreDirectories) {
                filesystem.mkdirs(resolved);
                resolved = resolved.resolve("data");
              }
              filesystem.writeContentsToPath(data, resolved);
            }

            @Override
            public StepExecutionResult execute(ExecutionContext context) throws IOException {
              writeOutput(output1);
              writeOutput(output2);
              return StepExecutionResults.SUCCESS;
            }
          });
    }
  }

  @Test
  public void testBuildRuleWithDuplicateOutputFiles() {
    ProcessResult result = workspace.runBuckBuild(DUPLICATE_OUTPUT_FILES_TARGET);
    result.assertSuccess();
  }

  @Test
  public void testBuildRuleWithDuplicateOutputDirs() {
    ProcessResult result = workspace.runBuckBuild(DUPLICATE_OUTPUT_DIRS_TARGET);
    result.assertSuccess();
  }

  @Test
  public void testBuildFailingRule() throws Exception {
    ProcessResult result = workspace.runBuckBuild(FAILING_TARGET);
    result.assertFailure();
    workspace.getBuildLog().assertTargetFailed(FAILING_TARGET);
    String stderr = result.getStderr();
    assertThat(stderr, containsString(FailingRule.FAILING_RULE_MESSAGE));
    assertThat(stderr, containsString(FailingRule.RULE_CONSOLE_EVENT_MESSAGE));
    assertThat(stderr, containsString(FailingRule.RULE_CONSOLE_EVENT_INFO_MESSAGE));
  }

  @Test
  public void testBuildRuleWithFailingStep() throws Exception {
    ProcessResult result = workspace.runBuckBuild(FAILING_STEP_TARGET);
    result.assertFailure();
    workspace.getBuildLog().assertTargetFailed(FAILING_STEP_TARGET);
    String stderr = result.getStderr();
    assertThat(stderr, containsString(FailingRule.FAILING_STEP_MESSAGE));
    assertThat(stderr, containsString(FailingRule.STEP_CONSOLE_EVENT_MESSAGE));
    assertThat(stderr, containsString(FailingRule.RULE_CONSOLE_EVENT_INFO_MESSAGE));
  }

  @Test
  public void testBuildThenFetchFromCache() throws Exception {
    workspace.enableDirCache();
    ProcessResult result = workspace.runBuckBuild(SIMPLE_TARGET);
    result.assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(SIMPLE_TARGET);
    workspace.runBuckCommand("clean", "--keep-cache");
    result = workspace.runBuckBuild(SIMPLE_TARGET);
    result.assertSuccess();
    workspace.getBuildLog().assertTargetWasFetchedFromCache(SIMPLE_TARGET);
    assertEquals(
        "",
        workspace.getFileContents(
            new DefaultOutputPathResolver(filesystem, BuildTargetFactory.newInstance(SIMPLE_TARGET))
                .resolvePath(new OutputPath("some.path"))));
  }

  @Test
  public void testRuleReferencingLargeDynamics() throws Exception {
    ProcessResult result = workspace.runBuckBuild(LARGE_DYNAMIC_TARGET);
    result.assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(LARGE_DYNAMIC_TARGET);
    assertEquals(
        "a2\n",
        workspace.getFileContents(
            new DefaultOutputPathResolver(
                    filesystem, BuildTargetFactory.newInstance(LARGE_DYNAMIC_TARGET))
                .resolvePath(new OutputPath("some.path"))));
  }

  @Test
  public void testRuleReferencingHugeDynamics() throws Exception {
    ProcessResult result = workspace.runBuckBuild(HUGE_DYNAMIC_TARGET);
    result.assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(HUGE_DYNAMIC_TARGET);
    assertEquals(
        "a28b2a26b2c2a28b2a26b2c2d2a26b2a26b2c2a28b2a26b2c2a28b2a26b2c2d2a26b2a26b2c2e2\n",
        workspace.getFileContents(
            new DefaultOutputPathResolver(
                    filesystem, BuildTargetFactory.newInstance(HUGE_DYNAMIC_TARGET))
                .resolvePath(new OutputPath("some.path"))));
  }

  private static class TouchOutput extends ModernBuildRule<TouchOutput> implements Buildable {
    @AddToRuleKey private final OutputPath output;

    protected TouchOutput(
        BuildTarget buildTarget,
        ProjectFilesystem filesystem,
        SourcePathRuleFinder finder,
        String output) {
      super(buildTarget, filesystem, finder, TouchOutput.class);
      this.output = new OutputPath(output);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      return ImmutableList.of(new TouchStep(filesystem, outputPathResolver.resolvePath(output)));
    }
  }

  private static class AppendableString implements AddsToRuleKey {
    @AddToRuleKey private final String value;

    private AppendableString(String value) {
      this.value = value;
    }
  }

  /**
   * This is just a silly class that creates a graph of references to other LargeDynamic and large
   * AppendableString. Serialization/deserialization handles large objects differently than small
   * objects so this is constructed to exercise those paths.
   */
  private static class LargeDynamic implements AddsToRuleKey {
    @AddToRuleKey private final Optional<LargeDynamic> firstRef;
    @AddToRuleKey private final Optional<LargeDynamic> secondRef;
    @AddToRuleKey private final ImmutableList<AddsToRuleKey> allRefs;

    private LargeDynamic(
        Optional<LargeDynamic> firstRef, Optional<LargeDynamic> secondRef, Character val) {
      this.firstRef = firstRef;
      this.secondRef = secondRef;
      ImmutableList.Builder<AddsToRuleKey> builder = ImmutableList.builder();
      firstRef.ifPresent(builder::add);
      secondRef.ifPresent(builder::add);
      builder.add(bigArg(val), bigArg(val));
      this.allRefs = builder.build();
    }

    private void append(FunnyStringBuilder builder) {
      firstRef.ifPresent(ref -> ref.append(builder));
      secondRef.ifPresent(ref -> ref.append(builder));
      allRefs.forEach(
          ref -> {
            if (ref instanceof LargeDynamic) {
              ((LargeDynamic) ref).append(builder);
            } else if (ref instanceof AppendableString) {
              builder.append(((AppendableString) ref).value.charAt(0));
            }
          });
    }

    private AppendableString bigArg(Character val) {
      char[] buf = new char[1000];
      Arrays.fill(buf, val);
      return new AppendableString(new String(buf));
    }
  }

  private static class LargeDynamics extends ModernBuildRule<LargeDynamics> implements Buildable {
    @AddToRuleKey private final OutputPath output;

    @AddToRuleKey private final LargeDynamic dynamic;

    protected LargeDynamics(
        BuildTarget buildTarget,
        ProjectFilesystem filesystem,
        SourcePathRuleFinder finder,
        Optional<LargeDynamics> firstRef,
        Optional<LargeDynamics> secondRef,
        Character val) {
      super(buildTarget, filesystem, finder, LargeDynamics.class);
      this.output = new OutputPath("some.path");
      this.dynamic =
          new LargeDynamic(
              firstRef.map(ref -> ref.dynamic), secondRef.map(ref -> ref.dynamic), val);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      FunnyStringBuilder builder = new FunnyStringBuilder();
      dynamic.append(builder);
      return ImmutableList.of(
          new WriteFileStep(
              filesystem, builder.build(), outputPathResolver.resolvePath(output), false));
    }
  }

  private static class FunnyStringBuilder {
    private Character curr = null;
    private int count = 0;
    private StringBuilder builder = new StringBuilder();

    public void append(char c) {
      if (Objects.equals(curr, c)) {
        count++;
      } else {
        apply();
        curr = c;
        count = 1;
      }
    }

    public void apply() {
      if (curr != null) {
        builder.append(curr);
        if (count > 1) {
          builder.append(count);
        }
      }
      curr = null;
      count = 0;
    }

    public String build() {
      apply();
      return builder.toString();
    }
  }
}
