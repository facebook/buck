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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ShTestDescriptionTest {

  @Test
  public void argsWithLocationMacro() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    BuildRule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    ShTestBuilder shTestBuilder =
        new ShTestBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setTest(new FakeSourcePath("test.sh"))
            .setArgs(ImmutableList.of("$(location //:dep)"));
    assertThat(shTestBuilder.findImplicitDeps(), Matchers.hasItem(dep.getBuildTarget()));
    ShTest shTest = shTestBuilder.build(resolver);
    assertThat(shTest.getBuildDeps(), Matchers.contains(dep));
    assertThat(
        Arg.stringify(shTest.getArgs(), pathResolver),
        Matchers.hasItem(pathResolver.getAbsolutePath(dep.getSourcePathToOutput()).toString()));
  }

  @Test
  public void envWithLocationMacro() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    BuildRule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    ShTestBuilder shTestBuilder =
        new ShTestBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setTest(new FakeSourcePath("test.sh"))
            .setEnv(ImmutableMap.of("LOC", "$(location //:dep)"));
    assertThat(shTestBuilder.findImplicitDeps(), Matchers.hasItem(dep.getBuildTarget()));
    ShTest shTest = shTestBuilder.build(resolver);
    assertThat(shTest.getBuildDeps(), Matchers.contains(dep));
    assertThat(
        Arg.stringify(shTest.getEnv(), pathResolver),
        Matchers.equalTo(
            ImmutableMap.of(
                "LOC", pathResolver.getAbsolutePath(dep.getSourcePathToOutput()).toString())));
  }

  @Test
  public void resourcesAffectRuleKey() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path resource = filesystem.getPath("resource");
    filesystem.touch(resource);

    SourcePath test = new FakeSourcePath(filesystem, "some_test");
    filesystem.touch(Paths.get("some_test"));

    // Create a test rule without resources attached.
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ShTest shTestWithoutResources =
        new ShTestBuilder(target).setTest(test).build(resolver, filesystem);
    RuleKey ruleKeyWithoutResource = getRuleKey(shTestWithoutResources);

    // Create a rule with a resource attached.
    resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ShTest shTestWithResources =
        new ShTestBuilder(target)
            .setTest(test)
            .setResources(ImmutableSortedSet.of(resource))
            .build(resolver, filesystem);
    RuleKey ruleKeyWithResource = getRuleKey(shTestWithResources);

    // Verify that their rule keys are different.
    assertThat(ruleKeyWithoutResource, Matchers.not(Matchers.equalTo(ruleKeyWithResource)));
  }

  @Test
  public void resourcesAreInputs() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path resource = filesystem.getPath("resource");
    filesystem.touch(resource);
    TargetNode<?, ?> shTestWithResources =
        new ShTestBuilder(target)
            .setTest(new FakeSourcePath(filesystem, "some_test"))
            .setResources(ImmutableSortedSet.of(resource))
            .build();
    assertThat(shTestWithResources.getInputs(), Matchers.hasItem(resource));
  }

  private RuleKey getRuleKey(BuildRule rule) {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    FileHashCache fileHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(rule.getProjectFilesystem())));
    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(0, fileHashCache, pathResolver, ruleFinder);
    return factory.build(rule);
  }
}
