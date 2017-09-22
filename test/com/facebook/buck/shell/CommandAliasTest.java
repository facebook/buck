/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.shell;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SingleThreadedBuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.stream.Stream;
import org.junit.Test;

public class CommandAliasTest {
  private static final BuildTarget target = BuildTargetFactory.newInstance("//arbitrary:target");
  private static final ProjectFilesystem fs = new FakeProjectFilesystem();
  private static final BuildRuleParams params =
      new BuildRuleParams(ImmutableSortedSet::of, ImmutableSortedSet::of, ImmutableSortedSet.of());
  private static final SourcePathRuleFinder ruleFinder =
      new SourcePathRuleFinder(
          new SingleThreadedBuildRuleResolver(
              TargetGraphFactory.newInstance(), new DefaultTargetNodeToBuildRuleTransformer()));
  private static final BuildContext buildContext =
      FakeBuildContext.withSourcePathResolver(DefaultSourcePathResolver.from(ruleFinder));
  private static final Tool tool = new CommandTool.Builder().build();

  @Test
  public void hasNoBuildDeps() {
    assertEquals(
        ImmutableSortedSet.of(), new CommandAlias(target, fs, params, tool).getBuildDeps());
  }

  @Test
  public void hasNoBuildSteps() {
    assertEquals(
        ImmutableList.of(),
        new CommandAlias(target, fs, params, tool)
            .getBuildSteps(buildContext, new FakeBuildableContext()));
  }

  @Test
  public void exposesPassedInTool() {
    assertEquals(tool, new CommandAlias(target, fs, params, tool).getExecutableCommand());
  }

  @Test
  public void exposesAllDepsAsRuntimeDeps() {
    ImmutableSortedSet<BuildRule> declaredDeps =
        ImmutableSortedSet.of(new FakeBuildRule("//:a"), new FakeBuildRule("//:b"));
    ImmutableSortedSet<BuildRule> extraDeps = ImmutableSortedSet.of(new FakeBuildRule("//:c"));

    BuildRule[] toolDeps = {
      new FakeBuildRule("//:d"), new FakeBuildRule("//:e"),
    };

    Tool toolWithDeps = new CommandTool.Builder().addDep(toolDeps).build();

    BuildRuleParams paramsWithDeps =
        new BuildRuleParams(() -> declaredDeps, () -> extraDeps, ImmutableSortedSet.of());

    assertEquals(
        Stream.concat(Stream.concat(declaredDeps.stream(), extraDeps.stream()), Stream.of(toolDeps))
            .map(BuildRule::getBuildTarget)
            .collect(MoreCollectors.toImmutableSortedSet()),
        new CommandAlias(target, fs, paramsWithDeps, toolWithDeps)
            .getRuntimeDeps(ruleFinder)
            .collect(MoreCollectors.toImmutableSortedSet()));
  }
}
