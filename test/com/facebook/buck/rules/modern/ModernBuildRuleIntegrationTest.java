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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.immutables.value.Value;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ModernBuildRuleIntegrationTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths(true);

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws Exception {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "should_delete_temporary_files", tmpFolder);
    workspace.setKnownRuleTypesFactoryFactory(
        (executor,
            pluginManager,
            sandboxExecutionStrategyFactory,
            knownConfigurationDescriptions) ->
            cell ->
                KnownRuleTypes.of(
                    ImmutableList.of(new TemporaryWritingDescription()),
                    knownConfigurationDescriptions));
    workspace.setUp();
  }

  @Test
  public void shouldNotDeleteTemporaryFilesIfOptionIsFalse() throws Exception {
    // When not deleting temporary files, ensure the temporary file still exist.
    workspace.runBuckBuild("--config=build.delete_temporaries=false", "//:foo").assertSuccess();
    try (Stream<Path> paths =
        Files.find(
            workspace.getPath(workspace.getBuckPaths().getScratchDir()),
            255,
            (path, basicFileAttributes) -> path.endsWith("temporary_writing_rule_temp"))) {
      Assert.assertTrue("Temporary file should still exist", paths.count() > 0);
    }
  }

  @Test
  public void shouldDeleteTemporaryFilesIfOptionIsTrue() throws Exception {
    // When not deleting temporary files, ensure the temporary file still exist.
    workspace.runBuckBuild("--config=build.delete_temporaries=true", "//:foo").assertSuccess();
    try (Stream<Path> paths =
        Files.find(
            workspace.getPath(workspace.getBuckPaths().getScratchDir()),
            255,
            (path, basicFileAttributes) -> path.endsWith("temporary_writing_rule_temp"))) {
      Assert.assertTrue("Temporary file should be deleted", paths.count() == 0);
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractTemporaryWritingDescriptionArg {}

  private static class TemporaryWritingDescription
      implements DescriptionWithTargetGraph<TemporaryWritingDescriptionArg> {

    @Override
    public Class<TemporaryWritingDescriptionArg> getConstructorArgType() {
      return TemporaryWritingDescriptionArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        TemporaryWritingDescriptionArg args) {
      return new TemporaryWritingRule(
          buildTarget,
          context.getProjectFilesystem(),
          new SourcePathRuleFinder(context.getActionGraphBuilder()));
    }
  }

  private static class TemporaryWritingRule extends ModernBuildRule<TemporaryWritingRule>
      implements Buildable {

    @AddToRuleKey private final OutputPath output = new OutputPath("output");

    public TemporaryWritingRule(
        BuildTarget buildTarget, ProjectFilesystem filesystem, SourcePathRuleFinder finder) {
      super(buildTarget, filesystem, finder, TemporaryWritingRule.class);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      Path tmp = outputPathResolver.getTempPath("temporary_writing_rule_temp");
      return ImmutableList.of(
          new WriteFileStep(filesystem, "", tmp, false),
          CopyStep.forFile(filesystem, tmp, outputPathResolver.resolvePath(output)));
    }
  }
}
