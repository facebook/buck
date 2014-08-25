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

public class YaccTest {

  private static final SourcePath DEFAULT_YACC = new TestSourcePath("yacc");
  private static final ImmutableList<String> DEFAULT_FLAGS = ImmutableList.of("-flag");
  private static final Path DEFAULT_OUTPUT_PREFIX = Paths.get("output.prefix");
  private static final SourcePath DEFAULT_INPUT = new TestSourcePath("input");

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
                    "yacc", Strings.repeat("a", 40),
                    "input", Strings.repeat("b", 40),
                    "different", Strings.repeat("c", 40))));

    // Generate a rule key for the defaults.
    RuleKey.Builder.RuleKeyPair defaultRuleKey = generateRuleKey(
        ruleKeyBuilderFactory,
        new Yacc(
            params,
            DEFAULT_YACC,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT_PREFIX,
            DEFAULT_INPUT));

    // Verify that changing the archiver causes a rulekey change.
    RuleKey.Builder.RuleKeyPair yaccChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new Yacc(
            params,
            new TestSourcePath("different"),
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT_PREFIX,
            DEFAULT_INPUT));
    assertNotEquals(defaultRuleKey.getTotalRuleKey(), yaccChange.getTotalRuleKey());

    // Verify that changing the flags causes a rulekey change.
    RuleKey.Builder.RuleKeyPair flagsChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new Yacc(
            params,
            DEFAULT_YACC,
            ImmutableList.of("-different"),
            DEFAULT_OUTPUT_PREFIX,
            DEFAULT_INPUT));
    assertNotEquals(defaultRuleKey.getTotalRuleKey(), flagsChange.getTotalRuleKey());

    // Verify that changing the output prefix causes a rulekey change.
    RuleKey.Builder.RuleKeyPair outputPrefixChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new Yacc(
            params,
            DEFAULT_YACC,
            DEFAULT_FLAGS,
            Paths.get("different"),
            DEFAULT_INPUT));
    assertNotEquals(defaultRuleKey.getTotalRuleKey(), outputPrefixChange.getTotalRuleKey());

    // Verify that changing the inputs causes a rulekey change.
    RuleKey.Builder.RuleKeyPair inputChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new Yacc(
            params,
            DEFAULT_YACC,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT_PREFIX,
            new TestSourcePath("different")));
    assertNotEquals(defaultRuleKey.getTotalRuleKey(), inputChange.getTotalRuleKey());
  }

}
