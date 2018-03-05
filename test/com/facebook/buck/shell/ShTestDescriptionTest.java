/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.shell;

import static org.junit.Assert.assertThat;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ShTestDescriptionTest {

  @Test
  public void argsWithLocationMacro() throws Exception {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    BuildRule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    ShTestBuilder shTestBuilder =
        new ShTestBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setTest(FakeSourcePath.of("test.sh"))
            .setArgs(
                ImmutableList.of(
                    StringWithMacrosUtils.format("%s", LocationMacro.of(dep.getBuildTarget()))));
    assertThat(shTestBuilder.build().getParseDeps(), Matchers.hasItem(dep.getBuildTarget()));
    ShTest shTest = shTestBuilder.build(resolver);
    assertThat(shTest.getBuildDeps(), Matchers.contains(dep));
    assertThat(
        Arg.stringify(shTest.getArgs(), pathResolver),
        Matchers.hasItem(pathResolver.getAbsolutePath(dep.getSourcePathToOutput()).toString()));
  }

  @Test
  public void envWithLocationMacro() throws Exception {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    BuildRule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    ShTestBuilder shTestBuilder =
        new ShTestBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setTest(FakeSourcePath.of("test.sh"))
            .setEnv(
                ImmutableMap.of(
                    "LOC",
                    StringWithMacrosUtils.format("%s", LocationMacro.of(dep.getBuildTarget()))));
    assertThat(shTestBuilder.build().getParseDeps(), Matchers.hasItem(dep.getBuildTarget()));
    ShTest shTest = shTestBuilder.build(resolver);
    assertThat(shTest.getBuildDeps(), Matchers.contains(dep));
    assertThat(
        Arg.stringify(shTest.getEnv(), pathResolver),
        Matchers.equalTo(
            ImmutableMap.of(
                "LOC", pathResolver.getAbsolutePath(dep.getSourcePathToOutput()).toString())));
  }

  @Test
  public void resourcesAreInputs() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path resource = filesystem.getPath("resource");
    filesystem.touch(resource);
    TargetNode<?, ?> shTestWithResources =
        new ShTestBuilder(target)
            .setTest(FakeSourcePath.of(filesystem, "some_test"))
            .setResources(ImmutableSortedSet.of(resource))
            .build();
    assertThat(shTestWithResources.getInputs(), Matchers.hasItem(resource));
  }
}
