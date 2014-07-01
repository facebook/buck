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
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.BuckConstant;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

/**
 * Unit test for {@link BuildConfig}.
 */
public class BuildConfigTest {

  /**
   * Tests the following methods:
   * <ul>
   *   <li>{@link AbstractBuildRule#getInputsToCompareToOutput()}
   *   <li>{@link BuildConfig#getPathToOutputFile()}
   * </ul>
   */
  @Test
  public void testSimpleObserverMethods() {
    BuildConfig buildConfig = createSimpleBuildConfigRule();

    assertEquals(
        BuckConstant.GEN_PATH.resolve("java/com/example/__build_config__/BuildConfig.java"),
        buildConfig.getPathToOutputFile());
  }

  @Test
  public void testBuildInternal() throws IOException {
    BuildConfig buildConfig = createSimpleBuildConfigRule();

    // Mock out a BuildContext whose DependencyGraph will be traversed.
    BuildContext buildContext = EasyMock.createMock(BuildContext.class);
    EasyMock.replay(buildContext);

    List<Step> steps = buildConfig.getBuildSteps(buildContext, new FakeBuildableContext());
    Step generateBuildConfigStep = steps.get(1);
    GenerateBuildConfigStep expectedStep = new GenerateBuildConfigStep(
            /* appPackage */ "com.example",
            /* debug */ true,
        BuckConstant.GEN_PATH.resolve("java/com/example/__build_config__/BuildConfig.java"));
    assertEquals(expectedStep, generateBuildConfigStep);
  }

  @Test
  public void testGetTypeMethodOfBuilder() {
    assertEquals("build_config", BuildConfigDescription.TYPE.getName());
  }

  private static BuildConfig createSimpleBuildConfigRule() {
    // First, create the BuildConfig object.
    BuildTarget buildTarget = BuildTarget.builder("//java/com/example", "build_config").build();
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(buildTarget)
        .setType(BuildConfigDescription.TYPE)
        .build();
    return new BuildConfig(
        params,
        /* appPackage */ "com.example",
        /* debug */ true);
  }

  // TODO(nickpalmer): Add another unit test that passes in a non-trivial DependencyGraph and verify
  // that the resulting set of libraryManifestPaths is computed correctly.
}
