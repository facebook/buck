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

package com.facebook.buck.rules;

import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Paths;

public class CommandToolTest {

  @Test
  public void buildTargetSourcePath() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Build a source path which wraps a build rule.
    BuildRule rule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("output")
            .build(resolver, filesystem);
    SourcePath path = new BuildTargetSourcePath(rule.getBuildTarget());

    // Test command and inputs for just passing the source path.
    CommandTool tool =
        new CommandTool.Builder()
            .addArg(new SourcePathArg(pathResolver, path))
            .build();
    assertThat(
        tool.getCommandPrefix(pathResolver),
        Matchers.contains(
            Preconditions.checkNotNull(rule.getPathToOutput()).toAbsolutePath().toString()));
    assertThat(
        tool.getDeps(pathResolver),
        Matchers.contains(rule));
    assertThat(
        tool.getInputs(),
        Matchers.contains(path));
  }

  @Test
  public void pathSourcePath() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Build a source path which wraps a build rule.
    SourcePath path = new PathSourcePath(filesystem, Paths.get("output"));

    // Test command and inputs for just passing the source path.
    CommandTool tool =
        new CommandTool.Builder()
            .addArg(new SourcePathArg(pathResolver, path))
            .build();
    assertThat(
        tool.getCommandPrefix(pathResolver),
        Matchers.contains(pathResolver.getAbsolutePath(path).toString()));
  }

  @Test
  public void extraInputs() {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    BuildRule rule = new FakeBuildRule("//some:target", pathResolver);
    ruleResolver.addToIndex(rule);
    SourcePath path = new BuildTargetSourcePath(rule.getBuildTarget());

    CommandTool tool =
        new CommandTool.Builder()
            .addInputs(ImmutableList.of(path))
            .build();

    assertThat(
        tool.getDeps(pathResolver),
        Matchers.contains(rule));
    assertThat(
        tool.getInputs(),
        Matchers.contains(path));
  }

  @Test
  public void environment() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    SourcePath path = new FakeSourcePath("input");
    CommandTool tool =
        new CommandTool.Builder()
            .addArg("runit")
            .addEnv("PATH", new SourcePathArg(pathResolver, path))
            .build();

    assertThat(tool.getEnvironment(), Matchers.hasEntry(
            Matchers.equalTo("PATH"),
            Matchers.containsString("input")));
  }

  @Test
  public void sourcePathsContributeToRuleKeys() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    SourcePath path = new FakeSourcePath("input");
    CommandTool tool =
        new CommandTool.Builder()
            .addArg(new SourcePathArg(pathResolver, path))
            .build();

    FileHashCache hashCache = FakeFileHashCache.createFromStrings(
        ImmutableMap.of(
            "input", Strings.repeat("a", 40)));
    DefaultRuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(0, hashCache, pathResolver);
    RuleKey ruleKey = new UncachedRuleKeyBuilder(
        pathResolver,
        hashCache,
        ruleKeyBuilderFactory)
        .setReflectively("key",  tool)
        .build();

    hashCache = FakeFileHashCache.createFromStrings(
        ImmutableMap.of(
            "input", Strings.repeat("b", 40)));
    ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(0, hashCache, pathResolver);
    RuleKey changedRuleKey = new UncachedRuleKeyBuilder(
        pathResolver,
        hashCache,
        ruleKeyBuilderFactory)
        .setReflectively("key",  tool)
        .build();

    assertThat(ruleKey, Matchers.not(Matchers.equalTo(changedRuleKey)));
  }

}
