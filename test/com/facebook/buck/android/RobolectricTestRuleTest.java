/*
 * Copyright 2013-present Facebook, Inc.
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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class RobolectricTestRuleTest {

  private class ResourceRule implements HasAndroidResourceDeps {
    private final String resourceDirectory;

    public ResourceRule(String resourceDirectory) {
      this.resourceDirectory = resourceDirectory;
    }

    @Override
    public String getPathToTextSymbolsFile() {
      return null;
    }

    @Override
    public String getRDotJavaPackage() {
      return null;
    }

    @Override
    public String getRes() {
      return resourceDirectory;
    }

    @Override
    public boolean hasWhitelistedStrings() {
      return false;
    }

    @Override
    public BuildTarget getBuildTarget() {
      return null;
    }
  }

  @Test
  public void testRobolectricContainsAllResourceDependenciesInResVmArg() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    ImmutableList.Builder<HasAndroidResourceDeps> resDepsBuilder =
        ImmutableList.builder();
    for (int i = 0; i < 10; i++) {
      resDepsBuilder.add(new ResourceRule("java/src/com/facebook/base/" + i + "/res"));
    }
    ImmutableList<HasAndroidResourceDeps> resDeps = resDepsBuilder.build();

    BuildTarget robolectricBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base/robolectricTest:robolectricTest");

    RobolectricTestRule testRule = (RobolectricTestRule)ruleResolver.buildAndAddToIndex(
        RobolectricTestRule.newRobolectricTestRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
            .setBuildTarget(robolectricBuildTarget));

    String result = testRule.getRobolectricResourceDirectories(resDeps);
    for (HasAndroidResourceDeps dep : resDeps) {
      assertTrue(result.contains(dep.getRes()));
    }
  }

  @Test
  public void testRobolectricResourceDependenciesVmArgHasCorrectFormat() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    String resDep1 = "res1";
    String resDep2 = "res2";
    String resDep3 = "res3";
    StringBuilder expectedVmArgBuilder = new StringBuilder();
    expectedVmArgBuilder.append("-D")
        .append(RobolectricTestRule.LIST_OF_RESOURCE_DIRECTORIES_PROPERTY_NAME)
        .append("=")
        .append(resDep1)
        .append(File.pathSeparator)
        .append(resDep2)
        .append(File.pathSeparator)
        .append(resDep3);

    BuildTarget robolectricBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base/robolectricTest:robolectricTest");

    RobolectricTestRule testRule = (RobolectricTestRule)ruleResolver.buildAndAddToIndex(
        RobolectricTestRule.newRobolectricTestRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
            .setBuildTarget(robolectricBuildTarget));

    String result = testRule.getRobolectricResourceDirectories(
        ImmutableList.<HasAndroidResourceDeps>of(
            new ResourceRule(resDep1),
            new ResourceRule(resDep2),
            new ResourceRule(resDep3)));

    assertEquals(expectedVmArgBuilder.toString(), result);
  }
}
