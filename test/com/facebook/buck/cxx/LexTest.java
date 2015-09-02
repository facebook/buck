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
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class LexTest {

  private static final Tool DEFAULT_LEX = new HashedFileTool(Paths.get("lex"));
  private static final ImmutableList<String> DEFAULT_FLAGS = ImmutableList.of("-flag");
  private static final Path DEFAULT_OUTPUT_SOURCE = Paths.get("output.source");
  private static final Path DEFAULT_OUTPUT_HEADER = Paths.get("output.header");
  private static final SourcePath DEFAULT_INPUT = new TestSourcePath("input");

  private RuleKey generateRuleKey(
      RuleKeyBuilderFactory factory,
      AbstractBuildRule rule) {

    RuleKeyBuilder builder = factory.newInstance(rule);
    return builder.build();
  }

  @Test
  public void testThatInputChangesCauseRuleKeyChanges() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    FakeFileHashCache hashCache =
        FakeFileHashCache.createFromStrings(
            ImmutableMap.of(
                "lex", Strings.repeat("a", 40),
                "input", Strings.repeat("b", 40)));
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
            hashCache,
            pathResolver);

    // Generate a rule key for the defaults.
    RuleKey defaultRuleKey = generateRuleKey(
        ruleKeyBuilderFactory,
        new Lex(
            params,
            pathResolver,
            DEFAULT_LEX,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT_SOURCE,
            DEFAULT_OUTPUT_HEADER,
            DEFAULT_INPUT));

    // Verify that changing the archiver causes a rulekey change.
    RuleKey lexChange = generateRuleKey(
        new DefaultRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.of(
                    "lex", Strings.repeat("f", 40),
                    "input", Strings.repeat("b", 40))),
            pathResolver),
        new Lex(
            params,
            pathResolver,
            DEFAULT_LEX,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT_SOURCE,
            DEFAULT_OUTPUT_HEADER,
            DEFAULT_INPUT));
    assertNotEquals(defaultRuleKey, lexChange);

    // Verify that changing the flags causes a rulekey change.
    RuleKey flagsChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new Lex(
            params,
            pathResolver,
            DEFAULT_LEX,
            ImmutableList.of("-different"),
            DEFAULT_OUTPUT_SOURCE,
            DEFAULT_OUTPUT_HEADER,
            DEFAULT_INPUT));
    assertNotEquals(defaultRuleKey, flagsChange);

    // Verify that changing the output source causes a rulekey change.
    RuleKey outputSourceChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new Lex(
            params,
            pathResolver,
            DEFAULT_LEX,
            DEFAULT_FLAGS,
            Paths.get("different"),
            DEFAULT_OUTPUT_HEADER,
            DEFAULT_INPUT));
    assertNotEquals(defaultRuleKey, outputSourceChange);

    // Verify that changing the output header causes a rulekey change.
    RuleKey outputHeaderChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new Lex(
            params,
            pathResolver,
            DEFAULT_LEX,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT_SOURCE,
            Paths.get("different"),
            DEFAULT_INPUT));
    assertNotEquals(defaultRuleKey, outputHeaderChange);

    // Verify that changing the inputs causes a rulekey change.
    RuleKey inputChange = generateRuleKey(
        new DefaultRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.of(
                    "lex", Strings.repeat("a", 40),
                    "input", Strings.repeat("f", 40))),
            pathResolver),
        new Lex(
            params,
            pathResolver,
            DEFAULT_LEX,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT_SOURCE,
            DEFAULT_OUTPUT_HEADER,
            DEFAULT_INPUT));
    assertNotEquals(defaultRuleKey, inputChange);
  }

}
