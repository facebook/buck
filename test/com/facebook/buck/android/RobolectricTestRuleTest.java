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
import com.facebook.buck.rules.Sha1HashCode;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;

public class RobolectricTestRuleTest {

  private class ResourceRule implements HasAndroidResourceDeps {
    private final Path resourceDirectory;

    public ResourceRule(Path resourceDirectory) {
      this.resourceDirectory = resourceDirectory;
    }

    @Override
    public Path getPathToTextSymbolsFile() {
      return null;
    }

    @Override
    public Sha1HashCode getTextSymbolsAbiKey() {
      return null;
    }

    @Override
    public String getRDotJavaPackage() {
      return null;
    }

    @Override
    public Path getRes() {
      return resourceDirectory;
    }

    @Override
    public Path getAssets() {
      return null;
    }

    @Override
    public Collection<Path> getInputsToCompareToOutput() {
      return null;
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
    ImmutableList.Builder<HasAndroidResourceDeps> resDepsBuilder =
        ImmutableList.builder();
    for (int i = 0; i < 10; i++) {
      resDepsBuilder.add(new ResourceRule(Paths.get("java/src/com/facebook/base/" + i + "/res")));
    }
    ImmutableList<HasAndroidResourceDeps> resDeps = resDepsBuilder.build();

    BuildTarget robolectricBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base/robolectricTest:robolectricTest");

    RobolectricTest robolectricTest = RobolectricTestBuilder
        .createBuilder(robolectricBuildTarget)
        .build();

    String result = robolectricTest.getRobolectricResourceDirectories(resDeps);
    for (HasAndroidResourceDeps dep : resDeps) {
      assertTrue(result.contains(dep.getRes().toString()));
    }
  }

  @Test
  public void testRobolectricResourceDependenciesVmArgHasCorrectFormat() throws IOException {
    Path resDep1 = Paths.get("res1");
    Path resDep2 = Paths.get("res2");
    Path resDep3 = Paths.get("res3");
    StringBuilder expectedVmArgBuilder = new StringBuilder();
    expectedVmArgBuilder.append("-D")
        .append(RobolectricTest.LIST_OF_RESOURCE_DIRECTORIES_PROPERTY_NAME)
        .append("=")
        .append(resDep1)
        .append(File.pathSeparator)
        .append(resDep2)
        .append(File.pathSeparator)
        .append(resDep3);

    BuildTarget robolectricBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base/robolectricTest:robolectricTest");

    RobolectricTest robolectricTest = RobolectricTestBuilder
        .createBuilder(robolectricBuildTarget)
        .build();

    String result = robolectricTest.getRobolectricResourceDirectories(
        ImmutableList.<HasAndroidResourceDeps>of(
            new ResourceRule(resDep1),
            new ResourceRule(resDep2),
            new ResourceRule(resDep3)));

    assertEquals(expectedVmArgBuilder.toString(), result);
  }
}
