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

package com.facebook.buck.core.rules.common;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.SortedSet;
import javax.annotation.Nullable;
import org.junit.Test;

public class BuildableSupportTest {
  @Test
  public void testDeriveDepsFromAddsToRuleKeys() {
    BuildTarget target = BuildTargetFactory.newInstance(Paths.get("some"), "//some", "name");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildRule rule1 = makeRule(target, filesystem, "rule1");
    BuildRule rule2 = makeRule(target, filesystem, "rule2");
    BuildRule rule3 = makeRule(target, filesystem, "rule3");
    BuildRule rule4 = makeRule(target, filesystem, "rule4");
    BuildRule rule5 = makeRule(target, filesystem, "rule5");
    ImmutableSet<BuildRule> rules = ImmutableSet.of(rule1, rule2, rule3, rule4, rule5);
    rules.forEach(graphBuilder::addToIndex);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);

    AddsToRuleKey rule =
        new AddsToRuleKey() {
          @AddToRuleKey int value = 0;
          @AddToRuleKey BuildRule bareRule = rule1;
          @AddToRuleKey SourcePath sourcePath = rule2.getSourcePathToOutput();

          @AddToRuleKey
          Object ruleKeyAppendable =
              new AddsToRuleKey() {
                @AddToRuleKey Object key = rule3;
              };

          @AddToRuleKey ImmutableList<BuildRule> list = ImmutableList.of(rule4);
          @AddToRuleKey Optional<SourcePath> optional = Optional.of(rule5.getSourcePathToOutput());
        };

    MoreAsserts.assertSetEquals(
        rules,
        BuildableSupport.deriveDeps(rule, ruleFinder).collect(ImmutableSet.toImmutableSet()));
  }

  @Test
  public void testDeriveInputsFromAddsToRuleKeys() {
    PathSourcePath path1 = FakeSourcePath.of("path1");
    PathSourcePath path2 = FakeSourcePath.of("path2");
    PathSourcePath path3 = FakeSourcePath.of("path3");
    AddsToRuleKey rule =
        new AddsToRuleKey() {
          @AddToRuleKey int value = 0;
          @AddToRuleKey SourcePath sourcePath = path1;

          @AddToRuleKey
          Object ruleKeyAppendable =
              new AddsToRuleKey() {
                @AddToRuleKey SourcePath key = path2;
              };

          @AddToRuleKey Optional<SourcePath> optional = Optional.of(path3);
        };

    MoreAsserts.assertSetEquals(
        ImmutableSet.of(path1, path2, path3),
        BuildableSupport.deriveInputs(rule).collect(ImmutableSet.toImmutableSet()));
  }

  private BuildRule makeRule(BuildTarget target, ProjectFilesystem filesystem, String flavor) {
    return new AbstractBuildRule(
        target.withAppendedFlavors(InternalFlavor.of(flavor)), filesystem) {
      @Override
      public SortedSet<BuildRule> getBuildDeps() {
        return ImmutableSortedSet.of();
      }

      @Override
      public ImmutableList<? extends Step> getBuildSteps(
          BuildContext context, BuildableContext buildableContext) {
        return null;
      }

      @Nullable
      @Override
      public SourcePath getSourcePathToOutput() {
        return ExplicitBuildTargetSourcePath.of(getBuildTarget(), Paths.get("whatever"));
      }
    };
  }
}
