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

package com.facebook.buck.rules.keys;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.SourceArtifactImpl;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.core.rules.actions.ActionRegistryForTests;
import com.facebook.buck.core.rules.actions.FakeAction;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.impl.RuleAnalysisLegacyBuildRuleView;
import com.facebook.buck.core.rules.providers.collect.impl.LegacyProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.shell.GenruleBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.events.Location;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ContentAgnosticRuleKeyFactoryTest {

  @Test
  public void ruleKeyDoesNotChangeWhenFileContentsChange() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Changing the contents of a file should not alter the rulekey.
    RuleKey ruleKey1 = createRuleKey(filesystem, "output_file", "contents");
    RuleKey ruleKey2 = createRuleKey(filesystem, "output_file", "diff_contents");

    assertThat(ruleKey1, Matchers.equalTo(ruleKey2));
  }

  @Test
  public void ruleKeyDoesChangeWithFileRename() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // A file rename should alter the rulekey.
    RuleKey ruleKey1 = createRuleKey(filesystem, "output_file", "contents");
    RuleKey ruleKey2 = createRuleKey(filesystem, "output_file_2", "diff_contents");

    assertThat(ruleKey1, Matchers.not(Matchers.equalTo(ruleKey2)));
  }

  @Test
  public void ruleKeyDoesNotChangeWhenFileContentsChangeForActions() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Changing the contents of a file should not alter the rulekey.
    RuleKey ruleKey1 = createRuleKey(filesystem, "output_file", "contents");
    RuleKey ruleKey2 = createRuleKey(filesystem, "output_file", "diff_contents");

    assertThat(ruleKey1, Matchers.equalTo(ruleKey2));
  }

  @Test
  public void ruleKeyDoesChangeWithFileRenameForActions() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // A file rename should alter the rulekey.
    RuleKey ruleKey1 = createRuleKeyForAction(filesystem, "output_file", "contents");
    RuleKey ruleKey2 = createRuleKeyForAction(filesystem, "output_file_2", "diff_contents");

    assertThat(ruleKey1, Matchers.not(Matchers.equalTo(ruleKey2)));
  }

  private RuleKey createRuleKey(ProjectFilesystem fileSystem, String filename, String fileContents)
      throws Exception {
    RuleKeyFieldLoader fieldLoader =
        new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    Path depOutput = Paths.get(filename);
    FakeBuildRule dep =
        graphBuilder.addToIndex(
            new FakeBuildRule(BuildTargetFactory.newInstance("//:dep"), fileSystem));
    dep.setOutputFile(depOutput.toString());
    fileSystem.writeContentsToPath(
        fileContents,
        graphBuilder.getSourcePathResolver().getRelativePath(dep.getSourcePathToOutput()));

    BuildRule rule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut(filename)
            .setSrcs(ImmutableList.of(dep.getSourcePathToOutput()))
            .build(graphBuilder, fileSystem);

    return new ContentAgnosticRuleKeyFactory(fieldLoader, graphBuilder, Optional.empty())
        .build(rule);
  }

  private RuleKey createRuleKeyForAction(
      ProjectFilesystem fileSystem, String filename, String fileContents) throws Exception {
    RuleKeyFieldLoader fieldLoader =
        new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    PathSourcePath sourcePath = PathSourcePath.of(fileSystem, Paths.get(filename));

    fileSystem.writeContentsToPath(
        fileContents, graphBuilder.getSourcePathResolver().getRelativePath(sourcePath));

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:rule");
    ActionRegistryForTests actionRegistry = new ActionRegistryForTests(buildTarget);
    Artifact artifact = actionRegistry.declareArtifact(filename, Location.BUILTIN);

    Action action =
        new FakeAction(
            actionRegistry,
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of(SourceArtifactImpl.of(sourcePath)),
            ImmutableSortedSet.of(artifact),
            (ignored, ignored1, ignored2, ignored3) ->
                ActionExecutionResult.success(
                    Optional.empty(), Optional.empty(), ImmutableList.of()));
    BuildRule rule =
        new RuleAnalysisLegacyBuildRuleView(
            "rule",
            buildTarget,
            Optional.of(action),
            graphBuilder,
            fileSystem,
            LegacyProviderInfoCollectionImpl.of());

    return new ContentAgnosticRuleKeyFactory(fieldLoader, graphBuilder, Optional.empty())
        .build(rule);
  }
}
