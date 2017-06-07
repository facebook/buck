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
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.UncachedRuleKeyBuilder;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CommandToolTest {

  @Test
  public void buildTargetSourcePath() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Build a source path which wraps a build rule.
    BuildRule rule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("output")
            .build(resolver, filesystem);
    SourcePath path = rule.getSourcePathToOutput();

    // Test command and inputs for just passing the source path.
    CommandTool tool = new CommandTool.Builder().addArg(SourcePathArg.of(path)).build();
    assertThat(
        tool.getCommandPrefix(pathResolver),
        Matchers.contains(
            pathResolver
                .getAbsolutePath(Preconditions.checkNotNull(rule.getSourcePathToOutput()))
                .toString()));
    assertThat(tool.getDeps(ruleFinder), Matchers.contains(rule));
    assertThat(tool.getInputs(), Matchers.contains(path));
  }

  @Test
  public void pathSourcePath() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Build a source path which wraps a build rule.
    SourcePath path = new PathSourcePath(filesystem, Paths.get("output"));

    // Test command and inputs for just passing the source path.
    CommandTool tool = new CommandTool.Builder().addArg(SourcePathArg.of(path)).build();
    assertThat(
        tool.getCommandPrefix(pathResolver),
        Matchers.contains(pathResolver.getAbsolutePath(path).toString()));
  }

  @Test
  public void extraInputs() {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    FakeBuildRule rule = new FakeBuildRule("//some:target", pathResolver);
    rule.setOutputFile("foo");
    ruleResolver.addToIndex(rule);
    SourcePath path = rule.getSourcePathToOutput();

    CommandTool tool = new CommandTool.Builder().addInputs(ImmutableList.of(path)).build();

    assertThat(tool.getDeps(ruleFinder), Matchers.contains(rule));
    assertThat(tool.getInputs(), Matchers.contains(path));
  }

  @Test
  public void environment() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    SourcePath path = new FakeSourcePath("input");
    CommandTool tool =
        new CommandTool.Builder().addArg("runit").addEnv("PATH", SourcePathArg.of(path)).build();

    assertThat(
        tool.getEnvironment(pathResolver),
        Matchers.hasEntry(Matchers.equalTo("PATH"), Matchers.containsString("input")));
  }

  @Test
  public void sourcePathsContributeToRuleKeys() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    SourcePath path = new FakeSourcePath("input");
    CommandTool tool = new CommandTool.Builder().addArg(SourcePathArg.of(path)).build();

    FileHashCache hashCache =
        FakeFileHashCache.createFromStrings(ImmutableMap.of("input", Strings.repeat("a", 40)));
    DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder);
    RuleKey ruleKey =
        new UncachedRuleKeyBuilder(ruleFinder, pathResolver, hashCache, ruleKeyFactory)
            .setReflectively("key", tool)
            .build(RuleKey::new);

    hashCache =
        FakeFileHashCache.createFromStrings(ImmutableMap.of("input", Strings.repeat("b", 40)));
    ruleKeyFactory = new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder);
    RuleKey changedRuleKey =
        new UncachedRuleKeyBuilder(ruleFinder, pathResolver, hashCache, ruleKeyFactory)
            .setReflectively("key", tool)
            .build(RuleKey::new);

    assertThat(ruleKey, Matchers.not(Matchers.equalTo(changedRuleKey)));
  }
}
