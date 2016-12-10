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

package com.facebook.buck.rules.keys;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeAbiRuleBuildRule;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class AbiRuleKeyFactoryTest {

  @Test
  public void ruleKeyDoesNotChangeWhenOnlyDependencyRuleKeyChanges() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    Path depOutput = Paths.get("output");
    FakeBuildRule dep =
        resolver.addToIndex(
            new FakeBuildRule(BuildTargetFactory.newInstance("//:dep"), pathResolver));
    dep.setOutputFile(depOutput.toString());

    FakeFileHashCache hashCache = new FakeFileHashCache(
        ImmutableMap.of(depOutput, HashCode.fromInt(0)));
    DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver);

    BuildRule rule = new FakeAbiRuleBuildRule("//:rule", pathResolver, dep);

    RuleKey inputKey1 =
        new AbiRuleKeyFactory(0, hashCache, pathResolver, ruleKeyFactory)
            .build(rule);

    RuleKey inputKey2 =
        new AbiRuleKeyFactory(0, hashCache, pathResolver, ruleKeyFactory)
            .build(rule);

    assertThat(
        inputKey1,
        equalTo(inputKey2));
  }

  @Test
  public void ruleKeyChangesWhenAbiKeyChanges() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FakeFileHashCache hashCache = new FakeFileHashCache(
        ImmutableMap.of());
    DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver);

    FakeAbiRuleBuildRule rule = new FakeAbiRuleBuildRule("//:rule", pathResolver);

    rule.setAbiKey(Sha1HashCode.of(Strings.repeat("a", 40)));
    RuleKey inputKey1 =
        new AbiRuleKeyFactory(0, hashCache, pathResolver, ruleKeyFactory)
            .build(rule);

    rule.setAbiKey(Sha1HashCode.of(Strings.repeat("b", 40)));
    RuleKey inputKey2 =
        new AbiRuleKeyFactory(0, hashCache, pathResolver, ruleKeyFactory)
            .build(rule);

    assertThat(
        inputKey1,
        not(equalTo(inputKey2)));
  }

}
