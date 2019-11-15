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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
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
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.SortedSet;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.junit.Test;

public class PathUtilsTest {
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
        PathUtils.getUserFacingOutputPath(pathResolver, rule, true).get(),
        Matchers.equalTo(expected));
  }

  @Test
  public void resolvesWithoutBuckCompat() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule = new TestRule(buildTarget, fileSystem, Paths.get("foo"));
    graphBuilder.addToIndex(rule);
    Path expected = fileSystem.getRootPath().resolve("foo");

    assertThat(
        PathUtils.getUserFacingOutputPath(pathResolver, rule, false).get(),
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
}
