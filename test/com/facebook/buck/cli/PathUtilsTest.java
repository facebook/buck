/*
 * Copyright 2019-present Facebook, Inc.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.HasMultipleOutputs;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.SortedSet;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class PathUtilsTest {
  @Rule public final ExpectedException exception = ExpectedException.none();

  private final ProjectFilesystem fileSystem = new FakeProjectFilesystem();
  private ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
  private SourcePathResolverAdapter pathResolver =
      new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));

  @Test
  public void resolvesWithBuckCompat() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule =
        new TestRule(
            buildTarget,
            fileSystem,
            fileSystem.getBuckPaths().getConfiguredBuckOut().resolve("foo"));
    graphBuilder.addToIndex(rule);
    Path expected = fileSystem.resolve(fileSystem.getBuckPaths().getBuckOut()).resolve("foo");

    assertThat(
        PathUtils.getUserFacingOutputPath(pathResolver, rule, true, OutputLabel.DEFAULT, true)
            .get(),
        Matchers.equalTo(expected));
  }

  @Test
  public void resolvesWithoutBuckCompat() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule = new TestRule(buildTarget, fileSystem, Paths.get("foo"));
    graphBuilder.addToIndex(rule);
    Path expected = fileSystem.getRootPath().resolve("foo");

    assertThat(
        PathUtils.getUserFacingOutputPath(pathResolver, rule, false, OutputLabel.DEFAULT, true)
            .get(),
        Matchers.equalTo(expected));
  }

  @Test
  public void resolvesOutputLabel() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule =
        new TestRuleWithMultipleOutputs(
            buildTarget,
            new FakeProjectFilesystem(),
            null,
            ImmutableMap.of(
                "baz", Paths.get("baz"), "bar", Paths.get("bar"), "qux", Paths.get(("qux"))));
    graphBuilder.addToIndex(rule);
    Path expected = fileSystem.getRootPath().resolve("bar");

    assertThat(
        PathUtils.getUserFacingOutputPath(pathResolver, rule, false, new OutputLabel("bar"), true)
            .get(),
        Matchers.equalTo(expected));
  }

  @Test
  public void returnsEmptyIfRequestedLabelDoesNotExist() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule =
        new TestRuleWithMultipleOutputs(
            buildTarget,
            new FakeProjectFilesystem(),
            null,
            ImmutableMap.of(
                "baz", Paths.get("baz"), "bar", Paths.get("bar"), "qux", Paths.get(("qux"))));
    graphBuilder.addToIndex(rule);

    assertFalse(
        PathUtils.getUserFacingOutputPath(pathResolver, rule, false, new OutputLabel("dne"), true)
            .isPresent());
  }

  @Test
  public void throwsIfMultipleOutputsNotSupported() {
    exception.expect(IllegalStateException.class);
    exception.expectMessage(
        containsString("Multiple outputs not supported for test_rule target //:foo"));

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule = new TestRule(buildTarget, fileSystem, Paths.get("foo"));
    graphBuilder.addToIndex(rule);

    PathUtils.getUserFacingOutputPath(pathResolver, rule, false, new OutputLabel("label"), true);
  }

  @Test
  public void throwsIfShowOutputsFlagNotPassedForNonDefaultLabel() {
    exception.expect(HumanReadableException.class);
    exception.expectMessage(
        containsString(
            "test_rule_with_multiple_outputs target //:foo[label] should use --show-outputs"));

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule =
        new TestRuleWithMultipleOutputs(
            buildTarget, fileSystem, Paths.get("foo"), ImmutableMap.of("foo", Paths.get("foo")));
    graphBuilder.addToIndex(rule);

    PathUtils.getUserFacingOutputPath(pathResolver, rule, false, new OutputLabel("label"), false)
        .get();
  }

  @Test
  public void getsDefaultOutputPathForRulesSupportingMultipleOutputsWithoutShowOutputsFlag() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule =
        new TestRuleWithMultipleOutputs(
            buildTarget, fileSystem, Paths.get("bar"), ImmutableMap.of());
    graphBuilder.addToIndex(rule);
    Path expected = fileSystem.getRootPath().resolve("bar");

    assertThat(
        PathUtils.getUserFacingOutputPath(pathResolver, rule, false, OutputLabel.DEFAULT, false)
            .get(),
        Matchers.equalTo(expected));
  }

  private static class TestRule extends AbstractBuildRule {
    private final Path path;

    public TestRule(BuildTarget buildTarget, ProjectFilesystem projectFilesystem, Path path) {
      super(buildTarget, projectFilesystem);
      this.path = path;
    }

    @Override
    public final SortedSet<BuildRule> getBuildDeps() {
      return ImmutableSortedSet.of();
    }

    @Override
    public final ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      return ImmutableList.of();
    }

    @Override
    public final boolean hasBuildSteps() {
      return false;
    }

    @Nullable
    @Override
    public final SourcePath getSourcePathToOutput() {
      return ExplicitBuildTargetSourcePath.of(getBuildTarget(), path);
    }

    // Avoid a round-trip to the cache, as noop rules have no output.
    @Override
    public final boolean isCacheable() {
      return false;
    }
  }

  private static class TestRuleWithMultipleOutputs extends TestRule implements HasMultipleOutputs {
    private final ImmutableMap<String, Path> outputLabelToSource;

    protected TestRuleWithMultipleOutputs(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        Path source,
        ImmutableMap<String, Path> outputLabelToSource) {
      super(buildTarget, projectFilesystem, source);
      this.outputLabelToSource = outputLabelToSource;
    }

    @Override
    @Nullable
    public ImmutableSortedSet<SourcePath> getSourcePathToOutput(OutputLabel outputLabel) {
      if (!outputLabel.getLabel().isPresent()) {
        return ImmutableSortedSet.of(getSourcePathToOutput());
      }
      Path path = outputLabelToSource.get(outputLabel.getLabel().get());
      return path == null
          ? null
          : ImmutableSortedSet.of(ExplicitBuildTargetSourcePath.of(getBuildTarget(), path));
    }

    @Override
    public ImmutableMap<OutputLabel, ImmutableSortedSet<SourcePath>>
        getSourcePathsByOutputsLabels() {
      return ImmutableMap.of();
    }
  }
}
