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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.ErrorLogger.DeconstructedException;
import com.google.common.collect.ImmutableList;
import org.junit.Assert;
import org.junit.Test;

public class ModernBuildRuleTest {

  @Test
  public void shouldErrorWhenPublicOutputPathIsInsideTempPath() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleResolver buildRuleResolver = new TestActionGraphBuilder();
    SourcePathRuleFinder finder = new SourcePathRuleFinder(buildRuleResolver);
    ModernBuildRule rule = new InvalidPublicOutputPathBuildRule(target, filesystem, finder);

    BuildableContext buildableContext = new FakeBuildableContext();
    try {
      rule.recordOutputs(buildableContext);
      Assert.fail("Should have thrown an exception.");
    } catch (Exception e) {
      DeconstructedException deconstructed = ErrorLogger.deconstruct(e);
      Assert.assertThat(deconstructed.getRootCause(), instanceOf(IllegalStateException.class));
      Assert.assertThat(
          deconstructed.getMessage(true),
          containsString("PublicOutputPath should not be inside rule temporary directory"));
    }
  }

  static class InvalidPublicOutputPathBuildRule
      extends ModernBuildRule<InvalidPublicOutputPathBuildRule> implements Buildable {

    @AddToRuleKey private final OutputPath path;

    public InvalidPublicOutputPathBuildRule(
        BuildTarget buildTarget, ProjectFilesystem filesystem, SourcePathRuleFinder finder) {
      super(buildTarget, filesystem, finder, InvalidPublicOutputPathBuildRule.class);
      this.path = new PublicOutputPath(getOutputPathResolver().getTempPath("foo"));
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      return ImmutableList.of();
    }
  }
}
