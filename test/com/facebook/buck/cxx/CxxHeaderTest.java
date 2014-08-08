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
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxHeaderTest {

  private RuleKey.Builder.RuleKeyPair generateRuleKey(
      RuleKeyBuilderFactory factory,
      AbstractBuildRule rule) {

    RuleKey.Builder builder = factory.newInstance(rule);
    rule.appendToRuleKey(builder);
    return builder.build();
  }

  @Test
  public void changesToHeaderMapCauseRuleKeyChanges() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new FakeRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.of(
                    "header1.h", Strings.repeat("a", 40),
                    "header2.h", Strings.repeat("b", 40),
                    "header2b.h", Strings.repeat("b", 40),
                    "header3.h", Strings.repeat("c", 40))));

    // Setup a base rule key to compare to.
    ImmutableMap<Path, SourcePath> defaultHeaders = ImmutableMap.<Path, SourcePath>of(
        Paths.get("foo.h"), new TestSourcePath("header1.h"),
        Paths.get("bar.h"), new TestSourcePath("header2.h"));
    RuleKey.Builder.RuleKeyPair defaultRuleKey = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxHeader(params, defaultHeaders));

    // Modify the layout of one of the headers and verify that the rule key changes.
    ImmutableMap<Path, SourcePath> changeLayoutHeaders = ImmutableMap.<Path, SourcePath>of(
        Paths.get("hello.h"), new TestSourcePath("header1.h"),
        Paths.get("bar.h"), new TestSourcePath("header2.h"));
    RuleKey.Builder.RuleKeyPair changeLayoutRuleKey = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxHeader(params, changeLayoutHeaders));
    assertNotEquals(defaultRuleKey.getTotalRuleKey(), changeLayoutRuleKey.getTotalRuleKey());

    // Modify the contents of one of the headers and verify that the rule key changes.
    ImmutableMap<Path, SourcePath> changeContentsHeaders = ImmutableMap.<Path, SourcePath>of(
        Paths.get("foo.h"), new TestSourcePath("header1.h"),
        Paths.get("bar.h"), new TestSourcePath("header3.h"));
    RuleKey.Builder.RuleKeyPair changeContentsRuleKey = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxHeader(params, changeContentsHeaders));
    assertNotEquals(defaultRuleKey.getTotalRuleKey(), changeContentsRuleKey.getTotalRuleKey());

    // Add a new header and verify the rule key changes.
    ImmutableMap<Path, SourcePath> addHeaders = ImmutableMap.<Path, SourcePath>of(
        Paths.get("foo.h"), new TestSourcePath("header1.h"),
        Paths.get("bar.h"), new TestSourcePath("header2.h"),
        Paths.get("hello.h"), new TestSourcePath("header3.h"));
    RuleKey.Builder.RuleKeyPair addRuleKey = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxHeader(params, addHeaders));
    assertNotEquals(defaultRuleKey.getTotalRuleKey(), addRuleKey.getTotalRuleKey());

    // Change one of the destination paths to something with the same hash, and verify the
    // rule key does *not* change.
    ImmutableMap<Path, SourcePath> noopChangeHeaders = ImmutableMap.<Path, SourcePath>of(
        Paths.get("foo.h"), new TestSourcePath("header1.h"),
        Paths.get("bar.h"), new TestSourcePath("header2b.h"));
    RuleKey.Builder.RuleKeyPair noopChangeRuleKey = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxHeader(params, noopChangeHeaders));
    assertEquals(defaultRuleKey.getTotalRuleKey(), noopChangeRuleKey.getTotalRuleKey());
  }

}
