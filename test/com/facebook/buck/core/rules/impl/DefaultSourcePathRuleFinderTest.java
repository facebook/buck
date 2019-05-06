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
package com.facebook.buck.core.rules.impl;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class DefaultSourcePathRuleFinderTest {
  @Test
  public void getRuleCanGetRuleOfBuildTargetSoucePath() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    FakeBuildRule rule = new FakeBuildRule("//:foo");
    rule.setOutputFile("foo");
    graphBuilder.addToIndex(rule);
    SourcePath sourcePath = DefaultBuildTargetSourcePath.of(rule.getBuildTarget());

    assertEquals(Optional.of(rule), graphBuilder.getRule(sourcePath));
  }

  @Test
  public void getRuleCannotGetRuleOfPathSoucePath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathRuleFinder ruleFinder = new DefaultSourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePath sourcePath = PathSourcePath.of(projectFilesystem, Paths.get("foo"));

    assertEquals(Optional.empty(), ruleFinder.getRule(sourcePath));
  }

  @Test
  public void testFilterBuildRuleInputsExcludesPathSourcePaths() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    FakeBuildRule rule =
        new FakeBuildRule(BuildTargetFactory.newInstance("//java/com/facebook:facebook"));
    graphBuilder.addToIndex(rule);
    FakeBuildRule rule2 = new FakeBuildRule(BuildTargetFactory.newInstance("//bar:foo"));
    graphBuilder.addToIndex(rule2);
    FakeBuildRule rule3 = new FakeBuildRule(BuildTargetFactory.newInstance("//bar:baz"));
    graphBuilder.addToIndex(rule3);

    Iterable<? extends SourcePath> sourcePaths =
        ImmutableList.of(
            FakeSourcePath.of("java/com/facebook/Main.java"),
            DefaultBuildTargetSourcePath.of(rule.getBuildTarget()),
            FakeSourcePath.of("java/com/facebook/BuckConfig.java"),
            ExplicitBuildTargetSourcePath.of(rule2.getBuildTarget(), Paths.get("foo")),
            ForwardingBuildTargetSourcePath.of(rule3.getBuildTarget(), FakeSourcePath.of("bar")));
    Iterable<BuildRule> inputs = graphBuilder.filterBuildRuleInputs(sourcePaths);
    MoreAsserts.assertIterablesEquals(
        "Iteration order should be preserved: results should not be alpha-sorted.",
        ImmutableList.of(rule, rule2, rule3),
        inputs);
  }
}
