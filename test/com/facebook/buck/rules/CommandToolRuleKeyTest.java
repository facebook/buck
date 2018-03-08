/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.UncachedRuleKeyBuilder;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import java.util.function.Function;
import org.junit.Before;
import org.junit.Test;

public class CommandToolRuleKeyTest {

  private BuildRuleResolver resolver;
  private SourcePathRuleFinder ruleFinder;
  private SourcePathResolver pathResolver;

  @Before
  public void setUp() throws Exception {
    resolver = new TestBuildRuleResolver();
    ruleFinder = new SourcePathRuleFinder(resolver);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);
  }

  @Test
  public void sourcePathArgsContributeToRuleKeys() {
    String input = "input";
    SourcePath path = FakeSourcePath.of(input);
    CommandTool tool = command(SourcePathArg.of(path));

    RuleKey ruleKey = ruleKey(tool, fakeHashCache(input, Strings.repeat("a", 40)));
    RuleKey changedRuleKey = ruleKey(tool, fakeHashCache(input, Strings.repeat("b", 40)));

    assertThat(ruleKey, not(equalTo(changedRuleKey)));
  }

  @Test
  public void stringArgsContributeToRuleKeys() {
    CommandTool one = command(StringArg.of("one"));
    CommandTool two = command(StringArg.of("two"));

    assertThat(ruleKey(one), not(equalTo(ruleKey(two))));
  }

  @Test
  public void envKeysContributeToRuleKeys() {
    StringArg value = StringArg.of("arbitrary");

    CommandTool one = command(value, builder -> builder.addEnv("one", value));
    CommandTool two = command(value, builder -> builder.addEnv("two", value));

    assertThat(ruleKey(one), not(equalTo(ruleKey(two))));
  }

  @Test
  public void envValuesContributeToRuleKeys() {
    StringArg value = StringArg.of("arbitrary");

    CommandTool one = command(value, builder -> builder.addEnv("key", StringArg.of("one")));
    CommandTool two = command(value, builder -> builder.addEnv("key", StringArg.of("two")));

    assertThat(ruleKey(one), not(equalTo(ruleKey(two))));
  }

  @Test
  public void sourcePathEnvValuesContributeToRuleKeys() {
    String input = "input";
    SourcePath path = FakeSourcePath.of(input);
    CommandTool tool =
        command(
            StringArg.of("arbitrary"), builder -> builder.addEnv("key", SourcePathArg.of(path)));

    RuleKey ruleKey = ruleKey(tool, fakeHashCache(input, Strings.repeat("a", 40)));
    RuleKey changedRuleKey = ruleKey(tool, fakeHashCache(input, Strings.repeat("b", 40)));

    assertThat(ruleKey, not(equalTo(changedRuleKey)));
  }

  @Test
  public void orderOfEnvVarsIsIrrelevant() {
    StringArg arg = StringArg.of("arbitrary");

    CommandTool one =
        command(arg, builder -> builder.addEnv("APPLES", "bananas").addEnv("cherries", "Dates"));
    CommandTool two =
        command(arg, builder -> builder.addEnv("cherries", "Dates").addEnv("APPLES", "bananas"));

    assertThat(ruleKey(one), equalTo(ruleKey(two)));
  }

  private RuleKey ruleKey(CommandTool tool) {
    return ruleKey(tool, fakeHashCache(ImmutableMap.of()));
  }

  private RuleKey ruleKey(CommandTool tool, FileHashCache hashCache) {
    return new UncachedRuleKeyBuilder(
            ruleFinder, pathResolver, hashCache, ruleKeyFactory(hashCache))
        .setReflectively("key", tool)
        .build(RuleKey::new);
  }

  private DefaultRuleKeyFactory ruleKeyFactory(FileHashCache hashCache) {
    return new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder);
  }

  private static FakeFileHashCache fakeHashCache(String file, String hash) {
    return fakeHashCache(ImmutableMap.of(file, hash));
  }

  private static FakeFileHashCache fakeHashCache(ImmutableMap<String, String> files) {
    return FakeFileHashCache.createFromStrings(files);
  }

  private static CommandTool command(Arg firstArg) {
    return command(firstArg, builder -> builder);
  }

  private static CommandTool command(
      Arg firstArg, Function<CommandTool.Builder, CommandTool.Builder> build) {
    return build.apply(new CommandTool.Builder().addArg(firstArg)).build();
  }
}
