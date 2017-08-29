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

package com.facebook.buck.rules;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.MoreCollectors;
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
  public void testDeriveDepsFromAddsToRuleKeys() throws Exception {
    BuildTarget target = BuildTarget.of(Paths.get("some"), "//some", "name");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver ruleResolver =
        new DefaultBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule rule1 = makeRule(target, filesystem, "rule1");
    BuildRule rule2 = makeRule(target, filesystem, "rule2");
    BuildRule rule3 = makeRule(target, filesystem, "rule3");
    BuildRule rule4 = makeRule(target, filesystem, "rule4");
    BuildRule rule5 = makeRule(target, filesystem, "rule5");
    ImmutableSet<BuildRule> rules = ImmutableSet.of(rule1, rule2, rule3, rule4, rule5);
    rules.forEach(ruleResolver::addToIndex);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);

    AddsToRuleKey rule =
        new AddsToRuleKey() {
          @AddToRuleKey int value = 0;
          @AddToRuleKey BuildRule bareRule = rule1;
          @AddToRuleKey SourcePath sourcePath = rule2.getSourcePathToOutput();

          @AddToRuleKey
          Object ruleKeyAppendable = (RuleKeyAppendable) sink -> sink.setReflectively("key", rule3);

          @AddToRuleKey ImmutableList<BuildRule> list = ImmutableList.of(rule4);
          @AddToRuleKey Optional<SourcePath> optional = Optional.of(rule5.getSourcePathToOutput());
        };

    MoreAsserts.assertSetEquals(
        rules,
        BuildableSupport.deriveDeps(rule, ruleFinder).collect(MoreCollectors.toImmutableSet()));
  }

  private BuildRule makeRule(
      BuildTarget target, ProjectFilesystem filesystem, final String flavor) {
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
        return new ExplicitBuildTargetSourcePath(getBuildTarget(), Paths.get("whatever"));
      }
    };
  }
}
