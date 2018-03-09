/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.modern;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.modern.config.ModernBuildRuleConfig;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Collection;
import org.immutables.value.Value;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ModernBuildRuleStrategyIntegrationTest {
  private String simpleTarget = "//:simple";

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    ImmutableList.Builder<Object[]> dataBuilder = ImmutableList.builder();
    for (ModernBuildRuleConfig.Strategy strategy : ModernBuildRuleConfig.Strategy.values()) {
      dataBuilder.add(new Object[] {strategy});
    }
    return dataBuilder.build();
  }

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths(true);

  private final ModernBuildRuleConfig.Strategy strategy;
  private ProjectWorkspace workspace;
  private ProjectFilesystem filesystem;

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractTouchOutputDescriptionArg extends HasDeclaredDeps {
    String getOut();
  }

  private static class TouchOutputDescription implements Description<TouchOutputDescriptionArg> {
    @Override
    public Class<TouchOutputDescriptionArg> getConstructorArgType() {
      return TouchOutputDescriptionArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContext creationContext,
        BuildTarget buildTarget,
        BuildRuleParams params,
        TouchOutputDescriptionArg args) {
      return new TouchOutput(
          buildTarget,
          creationContext.getProjectFilesystem(),
          new SourcePathRuleFinder(creationContext.getBuildRuleResolver()),
          args.getOut());
    }
  }

  @Before
  public void setUp() throws InterruptedException, IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "strategies", tmpFolder);
    workspace.setKnownBuildRuleTypesFactoryFactory(
        (processExecutor, pluginManager, sandboxExecutionStrategyFactory) ->
            cell ->
                KnownBuildRuleTypes.builder()
                    .addDescriptions(new TouchOutputDescription())
                    .build());
    workspace.setUp();
    workspace.addBuckConfigLocalOption("modern_build_rule", "strategy", strategy.toString());
    filesystem = TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
  }

  public ModernBuildRuleStrategyIntegrationTest(ModernBuildRuleConfig.Strategy strategy) {
    this.strategy = strategy;
  }

  @Test
  public void testBuildSimpleRule() throws Exception {
    ProcessResult result = workspace.runBuckBuild(simpleTarget);
    result.assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(simpleTarget);
    assertEquals(
        "",
        workspace.getFileContents(
            new DefaultOutputPathResolver(filesystem, BuildTargetFactory.newInstance(simpleTarget))
                .resolvePath(new OutputPath("some.path"))));
  }

  @Test
  public void testBuildThenFetchFromCache() throws Exception {
    workspace.enableDirCache();
    ProcessResult result = workspace.runBuckBuild(simpleTarget);
    result.assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(simpleTarget);
    workspace.runBuckCommand("clean", "--keep-cache");
    result = workspace.runBuckBuild(simpleTarget);
    result.assertSuccess();
    workspace.getBuildLog().assertTargetWasFetchedFromCache(simpleTarget);
    assertEquals(
        "",
        workspace.getFileContents(
            new DefaultOutputPathResolver(filesystem, BuildTargetFactory.newInstance(simpleTarget))
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
}
