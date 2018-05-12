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

package com.facebook.buck.rules.keys;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.hasher.StringRuleKeyHasher;
import com.facebook.buck.testutil.DummyFileHashCache;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class RuleKeyDiagnosticsTest {

  @Test
  public void testTraversal() {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestBuildRuleResolver());
    SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);

    AddsToRuleKey app0 =
        new AddsToRuleKey() {
          @AddToRuleKey String k0 = "v0";
        };
    AddsToRuleKey app1 =
        new AddsToRuleKey() {
          @AddToRuleKey String k1 = "v1";
        };
    AddsToRuleKey app2 =
        new AddsToRuleKey() {
          @AddToRuleKey String k2 = "v2";
          @AddToRuleKey Object k20 = app0;
        };
    AddsToRuleKey app3 =
        new AddsToRuleKey() {
          @AddToRuleKey String k3 = "v3";
          @AddToRuleKey Object k30 = app0;
        };
    AddsToRuleKey app4 =
        new AddsToRuleKey() {
          @AddToRuleKey Object k42 = app2;
          @AddToRuleKey Object k43 = app3;
        };

    BuildRule dep1 = new FakeBuildRule("//fake:dep1");
    BuildRule dep2 =
        new FakeBuildRule("//fake:dep2") {
          @AddToRuleKey AddsToRuleKey a4 = app4;
        };
    BuildRule rule1 =
        new FakeBuildRule("//fake:rule1", dep1, dep2) {
          @AddToRuleKey AddsToRuleKey a1 = app1;
          @AddToRuleKey AddsToRuleKey a4 = app4;
        };

    RuleKeyFactoryWithDiagnostics<RuleKey> factory =
        new TestDefaultRuleKeyFactory(new DummyFileHashCache(), sourcePathResolver, ruleFinder);

    RuleKeyDiagnostics<RuleKey, String> ruleKeyDiagnostics1 =
        new RuleKeyDiagnostics<>(
            rule -> factory.buildForDiagnostics(rule, new StringRuleKeyHasher()),
            appendable -> factory.buildForDiagnostics(appendable, new StringRuleKeyHasher()));
    Map<RuleKey, String> diagKeys1 = new HashMap<>();
    {
      Map<RuleKey, Integer> count11 = new HashMap<>();
      ruleKeyDiagnostics1.processRule(
          dep2,
          result -> {
            diagKeys1.put(result.ruleKey, result.diagKey);
            count11.put(result.ruleKey, 1 + count11.getOrDefault(result.ruleKey, 0));
          });
      // elements {dep2, app4, app3, app2, app0}, each visited once
      Assert.assertEquals(
          "Unexpected invocation count: " + count11,
          5,
          count11.values().stream().mapToInt(c -> c).sum());
    }
    {
      Map<RuleKey, Integer> count12 = new HashMap<>();
      ruleKeyDiagnostics1.processRule(
          rule1,
          result -> {
            diagKeys1.put(result.ruleKey, result.diagKey);
            count12.put(result.ruleKey, 1 + count12.getOrDefault(result.ruleKey, 0));
          });
      // only new elements {rule1, app1}, each visited once
      Assert.assertEquals(
          "Unexpected invocation count: " + count12,
          2,
          count12.values().stream().mapToInt(c -> c).sum());
    }
    // now visit rules in different order
    RuleKeyDiagnostics<RuleKey, String> ruleKeyDiagnostics2 =
        new RuleKeyDiagnostics<>(
            rule -> factory.buildForDiagnostics(rule, new StringRuleKeyHasher()),
            appendable -> factory.buildForDiagnostics(appendable, new StringRuleKeyHasher()));
    Map<RuleKey, String> diagKeys2 = new HashMap<>();
    {
      Map<RuleKey, Integer> count21 = new HashMap<>();
      ruleKeyDiagnostics2.processRule(
          rule1,
          result -> {
            diagKeys2.put(result.ruleKey, result.diagKey);
            count21.put(result.ruleKey, 1 + count21.getOrDefault(result.ruleKey, 0));
          });
      // elements {rule1, app4, app3, app2, app1, app0}, each visited once
      Assert.assertEquals(
          "Unexpected invocation count: " + count21,
          6,
          count21.values().stream().mapToInt(c -> c).sum());
    }
    {
      Map<RuleKey, Integer> count22 = new HashMap<>();
      ruleKeyDiagnostics2.processRule(
          dep2,
          result -> {
            diagKeys2.put(result.ruleKey, result.diagKey);
            count22.put(result.ruleKey, 1 + count22.getOrDefault(result.ruleKey, 0));
          });
      // only new elements {dep2}, each visited once
      Assert.assertEquals(
          "Unexpected invocation count: " + count22,
          1,
          count22.values().stream().mapToInt(c -> c).sum());
    }

    Assert.assertEquals(diagKeys1, diagKeys2);
  }
}
