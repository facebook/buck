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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class AndroidManifestDescriptionTest {

  @Test
  public void testGeneratedSkeletonAppearsInDeps() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();

    BuildRule ruleWithOutput = new FakeBuildRule(
        GenruleDescription.TYPE,
        BuildTargetFactory.newInstance("//foo:bar"),
        new SourcePathResolver(buildRuleResolver)) {
      @Override
      public Path getPathToOutputFile() {
        return Paths.get("buck-out/gen/foo/bar/AndroidManifest.xml");
      }
    };
    BuildTargetSourcePath skeleton = new BuildTargetSourcePath(
        projectFilesystem,
        ruleWithOutput.getBuildTarget());
    buildRuleResolver.addToIndex(ruleWithOutput);

    AndroidManifestDescription.Arg arg = new AndroidManifestDescription.Arg();
    arg.skeleton = skeleton;
    arg.deps = Optional.of(ImmutableSortedSet.<BuildTarget>of());

    BuildRuleParams params = new FakeBuildRuleParamsBuilder("//foo:baz")
        .setDeps(buildRuleResolver.getAllRules(arg.deps.get()))
        .build();
    AndroidManifest androidManifest = new AndroidManifestDescription()
        .createBuildRule(params, buildRuleResolver, arg);

    assertEquals(
        ImmutableSortedSet.of(ruleWithOutput),
        androidManifest.getDeps());
  }
}
