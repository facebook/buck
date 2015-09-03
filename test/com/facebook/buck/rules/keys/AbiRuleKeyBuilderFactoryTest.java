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

import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeAbiRuleBuildRule;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class AbiRuleKeyBuilderFactoryTest {

  @Test
  public void ruleKeyDoesNotChangeWhenOnlyDependencyRuleKeyChanges() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    Path depOutput = Paths.get("output");
    FakeBuildRule dep =
        resolver.addToIndex(
            new FakeBuildRule(BuildTargetFactory.newInstance("//:dep"), pathResolver));
    dep.setOutputFile(depOutput.toString());

    FakeFileHashCache hashCache =
        new FakeFileHashCache(
            ImmutableMap.of(depOutput, HashCode.fromInt(0)));

    BuildRule rule = JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:rule"))
        .addDep(dep.getBuildTarget())
        .build(resolver);

    dep.setRuleKey(new RuleKey("aaaa"));
    RuleKey inputKey1 =
        new AbiRuleKeyBuilderFactory(hashCache, pathResolver).newInstance(rule).build();

    dep.setRuleKey(new RuleKey("bbbb"));
    RuleKey inputKey2 =
        new AbiRuleKeyBuilderFactory(hashCache, pathResolver).newInstance(rule).build();

    assertThat(
        inputKey1,
        equalTo(inputKey2));
  }

  @Test
  public void ruleKeyChangesWhenAbiKeyChanges() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FakeFileHashCache hashCache = new FakeFileHashCache(ImmutableMap.<Path, HashCode>of());

    FakeAbiRuleBuildRule rule = new FakeAbiRuleBuildRule("//:rule", pathResolver);

    rule.setAbiKey(Sha1HashCode.of(Strings.repeat("a", 40)));
    RuleKey inputKey1 =
        new AbiRuleKeyBuilderFactory(hashCache, pathResolver).newInstance(rule).build();

    rule.setAbiKey(Sha1HashCode.of(Strings.repeat("b", 40)));
    RuleKey inputKey2 =
        new AbiRuleKeyBuilderFactory(hashCache, pathResolver).newInstance(rule).build();

    assertThat(
        inputKey1,
        not(equalTo(inputKey2)));
  }

}
