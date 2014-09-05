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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxCompileTest {

  private static final Path DEFAULT_COMPILER = Paths.get("compiler");
  private static final ImmutableList<String> DEFAULT_FLAGS =
      ImmutableList.of("-fsanitize=address");
  private static final Path DEFAULT_OUTPUT = Paths.get("test.o");
  private static final SourcePath DEFAULT_INPUT = new TestSourcePath("test.cpp");
  private static final ImmutableMap<Path, SourcePath> DEFAULT_INCLUDES =
      ImmutableMap.<Path, SourcePath>of(Paths.get("test.h"), new TestSourcePath("foo/test.h"));
  private static final ImmutableList<Path> DEFAULT_INCLUDE_ROOTS = ImmutableList.of(
      Paths.get("foo/bar"),
      Paths.get("test"));
  private static final ImmutableList<Path> DEFAULT_SYSTEM_INCLUDE_ROOTS = ImmutableList.of(
      Paths.get("/usr/include"),
      Paths.get("/include"));

  private RuleKey.Builder.RuleKeyPair generateRuleKey(
      RuleKeyBuilderFactory factory,
      AbstractBuildRule rule) {

    RuleKey.Builder builder = factory.newInstance(rule);
    rule.appendToRuleKey(builder);
    return builder.build();
  }

  @Test
  public void testThatInputChangesCauseRuleKeyChanges() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new FakeRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.of(
                    "compiler", Strings.repeat("a", 40),
                    "test.o", Strings.repeat("b", 40),
                    "test.cpp", Strings.repeat("c", 40),
                    "different", Strings.repeat("d", 40),
                    "foo/test.h", Strings.repeat("e", 40))));

    // Generate a rule key for the defaults.
    RuleKey.Builder.RuleKeyPair defaultRuleKey = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxCompile(
            params,
            DEFAULT_COMPILER,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_SYSTEM_INCLUDE_ROOTS,
            DEFAULT_INCLUDES));

    // Verify that changing the compiler causes a rulekey change.
    RuleKey.Builder.RuleKeyPair compilerChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxCompile(
            params,
            Paths.get("different"),
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_SYSTEM_INCLUDE_ROOTS,
            DEFAULT_INCLUDES));
    assertNotEquals(defaultRuleKey, compilerChange);

    // Verify that changing the flags causes a rulekey change.
    RuleKey.Builder.RuleKeyPair flagsChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxCompile(
            params,
            DEFAULT_COMPILER,
            ImmutableList.of("-different"),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_SYSTEM_INCLUDE_ROOTS,
            DEFAULT_INCLUDES));
    assertNotEquals(defaultRuleKey, flagsChange);

    // Verify that changing the input causes a rulekey change.
    RuleKey.Builder.RuleKeyPair inputChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxCompile(
            params,
            DEFAULT_COMPILER,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            new TestSourcePath("different"),
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_SYSTEM_INCLUDE_ROOTS,
            DEFAULT_INCLUDES));
    assertNotEquals(defaultRuleKey, inputChange);

    // Verify that changing the includes does *not* cause a rulekey change, since we use a
    // different mechanism to track header changes.
    RuleKey.Builder.RuleKeyPair includesChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxCompile(
            params,
            DEFAULT_COMPILER,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            ImmutableList.of(Paths.get("different")),
            DEFAULT_SYSTEM_INCLUDE_ROOTS,
            DEFAULT_INCLUDES));
    assertEquals(defaultRuleKey, includesChange);

    // Verify that changing the system includes does *not* cause a rulekey change, since we use a
    // different mechanism to track header changes.
    RuleKey.Builder.RuleKeyPair systemIncludesChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxCompile(
            params,
            DEFAULT_COMPILER,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INCLUDE_ROOTS,
            ImmutableList.of(Paths.get("different")),
            DEFAULT_INCLUDES));
    assertEquals(defaultRuleKey, systemIncludesChange);
  }

}
