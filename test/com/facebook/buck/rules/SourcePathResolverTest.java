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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTargetFactory;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class SourcePathResolverTest {

  @Test
  public void resolvePathSourcePath() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    Path expectedPath = Paths.get("foo");
    SourcePath sourcePath = new PathSourcePath(expectedPath);

    assertEquals(expectedPath, pathResolver.getPath(sourcePath));
  }

  @Test
  public void resolveBuildRuleSourcePath() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    Path expectedPath = Paths.get("foo");
    BuildRule rule = new OutputOnlyBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:foo")).build(),
        expectedPath);
    SourcePath sourcePath = new BuildRuleSourcePath(rule);

    assertEquals(expectedPath, pathResolver.getPath(sourcePath));
  }

  @Test
  public void resolveBuildRuleSourcePathWithOverriddenOutputPath() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    Path expectedPath = Paths.get("foo");
    BuildRule rule = new OutputOnlyBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:foo")).build(),
        Paths.get("notfoo"));
    SourcePath sourcePath = new BuildRuleSourcePath(rule, expectedPath);

    assertEquals(expectedPath, pathResolver.getPath(sourcePath));
  }

  @Test
  public void resolveMixedPaths() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    Path pathSourcePathExpectedPath = Paths.get("foo");
    Path buildRuleSourcePathExpectedPath = Paths.get("bar");
    Path buildRuleWithOverriddenOutputPathExpectedPath = Paths.get("baz");
    SourcePath pathSourcePath = new PathSourcePath(pathSourcePathExpectedPath);
    BuildRule rule = new OutputOnlyBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:bar")).build(),
        buildRuleSourcePathExpectedPath);
    SourcePath buildRuleSourcePath = new BuildRuleSourcePath(rule);
    BuildRule ruleWithOverriddenOutputPath = new OutputOnlyBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:baz")).build(),
        Paths.get("notbaz"));
    SourcePath buildRuleSourcePathWithOverriddenOutputPath = new BuildRuleSourcePath(
        ruleWithOverriddenOutputPath,
        buildRuleWithOverriddenOutputPathExpectedPath);

    assertEquals(
        ImmutableList.of(
            pathSourcePathExpectedPath,
            buildRuleSourcePathExpectedPath,
            buildRuleWithOverriddenOutputPathExpectedPath),
        pathResolver.getAllPaths(
            ImmutableList.of(
                pathSourcePath,
                buildRuleSourcePath,
                buildRuleSourcePathWithOverriddenOutputPath)));
  }
}
