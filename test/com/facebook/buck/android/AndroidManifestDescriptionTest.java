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

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestCellBuilder;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import org.junit.Test;

public class AndroidManifestDescriptionTest {

  @Test
  public void testGeneratedSkeletonAppearsInDeps() {
    BuildRuleResolver buildRuleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule ruleWithOutput =
        new FakeBuildRule(
            BuildTargetFactory.newInstance("//foo:bar"),
            new SourcePathResolver(new SourcePathRuleFinder(buildRuleResolver))) {
          @Override
          public SourcePath getSourcePathToOutput() {
            return new ExplicitBuildTargetSourcePath(
                getBuildTarget(), Paths.get("buck-out/gen/foo/bar/AndroidManifest.xml"));
          }
        };
    SourcePath skeleton = ruleWithOutput.getSourcePathToOutput();
    buildRuleResolver.addToIndex(ruleWithOutput);

    AndroidManifestDescriptionArg arg =
        AndroidManifestDescriptionArg.builder().setName("baz").setSkeleton(skeleton).build();

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder("//foo:baz")
            .setDeclaredDeps(buildRuleResolver.getAllRules(arg.getDeps()))
            .build();
    BuildRule androidManifest =
        new AndroidManifestDescription()
            .createBuildRule(
                TargetGraph.EMPTY,
                params,
                buildRuleResolver,
                TestCellBuilder.createCellRoots(params.getProjectFilesystem()),
                arg);

    assertEquals(ImmutableSortedSet.of(ruleWithOutput), androidManifest.getBuildDeps());
  }
}
