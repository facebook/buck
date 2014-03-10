/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Unit test for {@link BuildConfig}.
 */
public class BuildConfigTest {

  /**
   * Tests the following methods:
   * <ul>
   *   <li>{@link Buildable#getInputsToCompareToOutput()}
   *   <li>{@link BuildConfig#getPathToOutputFile()}
   * </ul>
   */
  @Test
  public void testSimpleObserverMethods() {
    BuildRule buildRule = createSimpleBuildConfigRule();
    BuildConfig buildConfigRule = (BuildConfig) buildRule.getBuildable();

    assertEquals(
        BuckConstant.GEN_PATH.resolve("java/com/example/__build_config__/BuildConfig.java"),
        buildConfigRule.getPathToOutputFile());
  }

  @Test
  public void testBuildInternal() throws IOException {
    BuildRule buildRule = createSimpleBuildConfigRule();
    BuildConfig buildConfigRule = (BuildConfig) buildRule.getBuildable();

    // Mock out a BuildContext whose DependencyGraph will be traversed.
    BuildContext buildContext = EasyMock.createMock(BuildContext.class);
    EasyMock.replay(buildContext);

    List<Step> steps = buildConfigRule.getBuildSteps(buildContext, new FakeBuildableContext());
    Step generateBuildConfigStep = steps.get(2);
    assertEquals(
        new GenerateBuildConfigStep("com.example", true,
            BuckConstant.GEN_PATH.resolve("java/com/example/__build_config__/BuildConfig.java")),
        generateBuildConfigStep);

    EasyMock.verify(buildContext);
  }

  @Test
  public void testGetTypeMethodOfBuilder() {
    assertEquals("build_config", BuildConfigDescription.TYPE.getName());
  }

  private BuildRule createSimpleBuildConfigRule() {
    // First, create the BuildConfig object.
    BuildRuleParams buildRuleParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(
        new BuildTarget("//java/com/example", "build_config"));
    BuildConfigDescription description = new BuildConfigDescription();
    BuildConfigDescription.Arg arg = description.createUnpopulatedConstructorArg();
    arg.appPackage = "com.example";
    arg.debug = true;
    final Buildable buildConfig = description.createBuildable(buildRuleParams, arg);

    // Then create a BuildRule whose Buildable is the BuildConfig.
    return new FakeBuildRule(BuildRuleType.ANDROID_MANIFEST, buildRuleParams) {
      @Override
      public Buildable getBuildable() {
        return buildConfig;
      }
    };
  }

  // TODO(user): Add another unit test that passes in a non-trivial DependencyGraph and verify that
  // the resulting set of libraryManifestPaths is computed correctly.
}
