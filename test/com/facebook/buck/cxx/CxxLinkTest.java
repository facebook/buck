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
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxLinkTest {

  private static final Tool DEFAULT_LINKER = new HashedFileTool(Paths.get("ld"));
  private static final Path DEFAULT_OUTPUT = Paths.get("test.exe");
  private static final ImmutableList<SourcePath> DEFAULT_INPUTS = ImmutableList.<SourcePath>of(
      new TestSourcePath("a.o"),
      new TestSourcePath("b.o"),
      new TestSourcePath("libc.a"));
  private static final ImmutableList<String> DEFAULT_ARGS = ImmutableList.of(
      "-rpath",
      "/lib",
      "libc.a");

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
                ImmutableMap.of(
                    "ld", Strings.repeat("0", 40),
                    "a.o", Strings.repeat("a", 40),
                    "b.o", Strings.repeat("b", 40),
                    "libc.a", Strings.repeat("c", 40),
                    "different", Strings.repeat("d", 40))));

    // Generate a rule key for the defaults.
    RuleKey.Builder.RuleKeyPair defaultRuleKey = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxLink(
            params,
            pathResolver,
            DEFAULT_LINKER,
            DEFAULT_OUTPUT,
            DEFAULT_INPUTS,
            DEFAULT_ARGS));

    // Verify that changing the archiver causes a rulekey change.
    RuleKey.Builder.RuleKeyPair linkerChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxLink(
            params,
            pathResolver,
            new HashedFileTool(Paths.get("different")),
            DEFAULT_OUTPUT,
            DEFAULT_INPUTS,
            DEFAULT_ARGS));
    assertNotEquals(defaultRuleKey, linkerChange);

    // Verify that changing the output path causes a rulekey change.
    RuleKey.Builder.RuleKeyPair outputChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxLink(
            params,
            pathResolver,
            DEFAULT_LINKER,
            Paths.get("different"),
            DEFAULT_INPUTS,
            DEFAULT_ARGS));
    assertNotEquals(defaultRuleKey, outputChange);

    // Verify that changing the inputs causes a rulekey change.
    RuleKey.Builder.RuleKeyPair inputChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxLink(
            params,
            pathResolver,
            DEFAULT_LINKER,
            DEFAULT_OUTPUT,
            ImmutableList.<SourcePath>of(new TestSourcePath("different")),
            DEFAULT_ARGS));
    assertNotEquals(defaultRuleKey, inputChange);

    // Verify that changing the flags causes a rulekey change.
    RuleKey.Builder.RuleKeyPair flagsChange = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        new CxxLink(
            params,
            pathResolver,
            DEFAULT_LINKER,
            DEFAULT_OUTPUT,
            DEFAULT_INPUTS,
            ImmutableList.of("-different")));
    assertNotEquals(defaultRuleKey, flagsChange);

  }

}
