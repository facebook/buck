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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxCompileTest {

  private static final SourcePath DEFAULT_COMPILER = new TestSourcePath("compiler");
  private static final ImmutableList<String> DEFAULT_FLAGS =
      ImmutableList.of("-fsanitize=address");
  private static final Path DEFAULT_OUTPUT = Paths.get("test.o");
  private static final SourcePath DEFAULT_INPUT = new TestSourcePath("test.cpp");
  private static final Optional<CxxCompile.Plugin> DEFAULT_PLUGIN =
      Optional.of(new CxxCompile.Plugin(
              "name",
              Paths.get("path/to/a/plugin.so"),
              ImmutableList.of("-abcde")));
  private static final Optional<DebugPathSanitizer> DEBUG_PATH_SANITIZER = Optional.absent();

  private RuleKey.Builder.RuleKeyPair generateRuleKey(
      RuleKeyBuilderFactory factory,
      SourcePathResolver resolver,
      AbstractBuildRule rule) {

    RuleKey.Builder builder = factory.newInstance(rule, resolver);
    rule.appendToRuleKey(builder);
    return builder.build();
  }

  @Test
  public void testThatInputChangesCauseRuleKeyChanges() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new FakeRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("compiler", Strings.repeat("a", 40))
                    .put("test.o", Strings.repeat("b", 40))
                    .put("test.cpp", Strings.repeat("c", 40))
                    .put("different", Strings.repeat("d", 40))
                    .put("foo/test.h", Strings.repeat("e", 40))
                    .put("path/to/a/plugin.so", Strings.repeat("f", 40))
                    .put("path/to/a/different/plugin.so", Strings.repeat("a0", 40))
                    .build()));

    // Generate a rule key for the defaults.
    RuleKey.Builder.RuleKeyPair defaultRuleKey = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxCompile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            DEFAULT_PLUGIN,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEBUG_PATH_SANITIZER));

    // Verify that changing the compiler causes a rulekey change.
    RuleKey.Builder.RuleKeyPair compilerChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxCompile(
            params,
            pathResolver,
            new TestSourcePath("different"),
            DEFAULT_PLUGIN,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEBUG_PATH_SANITIZER));
    assertNotEquals(defaultRuleKey, compilerChange);

    // Verify that changing the flags causes a rulekey change.
    RuleKey.Builder.RuleKeyPair flagsChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxCompile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            DEFAULT_PLUGIN,
            ImmutableList.of("-different"),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEBUG_PATH_SANITIZER));
    assertNotEquals(defaultRuleKey, flagsChange);

    // Verify that changing the input causes a rulekey change.
    RuleKey.Builder.RuleKeyPair inputChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxCompile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            DEFAULT_PLUGIN,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            new TestSourcePath("different"),
            DEBUG_PATH_SANITIZER));
    assertNotEquals(defaultRuleKey, inputChange);

    // Verify that not using a plugin changes the key
    RuleKey.Builder.RuleKeyPair pluginAbsentChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxCompile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            Optional.<CxxCompile.Plugin>absent(),
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEBUG_PATH_SANITIZER));
    assertNotEquals(defaultRuleKey, pluginAbsentChange);

    // Verify that changing the plugin path changes the key
    RuleKey.Builder.RuleKeyPair pluginPathChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxCompile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            Optional.of(new CxxCompile.Plugin(
                    DEFAULT_PLUGIN.get().getName(),
                    Paths.get("path/to/a/different/plugin.so"),
                    DEFAULT_PLUGIN.get().getFlags())),
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEBUG_PATH_SANITIZER));
    assertNotEquals(defaultRuleKey, pluginPathChange);

    // Verify that changing the plugin flags change the key
    RuleKey.Builder.RuleKeyPair pluginFlagsChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxCompile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            Optional.of(new CxxCompile.Plugin(
                    DEFAULT_PLUGIN.get().getName(),
                    DEFAULT_PLUGIN.get().getPath(),
                    ImmutableList.of("-abcde", "-aeiou"))),
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEBUG_PATH_SANITIZER));
    assertNotEquals(defaultRuleKey, pluginFlagsChange);

    // Verify that changing the plugin name changes the key
    RuleKey.Builder.RuleKeyPair pluginNameChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxCompile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            Optional.of(new CxxCompile.Plugin(
                    "different_name",
                    DEFAULT_PLUGIN.get().getPath(),
                    DEFAULT_PLUGIN.get().getFlags())),
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEBUG_PATH_SANITIZER));
    assertNotEquals(defaultRuleKey, pluginNameChange);
  }

}
