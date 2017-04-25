/*
 * Copyright 2016-present Facebook, Inc.
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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyBuilder;
import com.facebook.buck.rules.keys.UncachedRuleKeyBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import org.junit.Test;

public class HeaderVerificationTest {

  private RuleKey getRuleKey(HeaderVerification headerVerification) {
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    FileHashCache fileHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(new FakeProjectFilesystem())));
    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(0, fileHashCache, resolver, ruleFinder);
    RuleKeyBuilder<HashCode> builder =
        new UncachedRuleKeyBuilder(ruleFinder, resolver, fileHashCache, factory);
    builder.setReflectively("headerVerification", headerVerification);
    return builder.build(RuleKey::new);
  }

  @Test
  public void modeAffectsRuleKey() {
    assertThat(
        getRuleKey(HeaderVerification.of(HeaderVerification.Mode.IGNORE)),
        not(equalTo(getRuleKey(HeaderVerification.of(HeaderVerification.Mode.ERROR)))));
  }

  @Test
  public void whitelistDoesNotAffectRuleKeyInIgnoredMode() {
    assertThat(
        getRuleKey(HeaderVerification.of(HeaderVerification.Mode.IGNORE)),
        equalTo(
            getRuleKey(
                HeaderVerification.of(
                    HeaderVerification.Mode.IGNORE,
                    ImmutableSortedSet.of(".*"),
                    ImmutableSortedSet.of()))));
  }

  @Test
  public void whitelistAffectsRuleKeyInErrorMode() {
    assertThat(
        getRuleKey(HeaderVerification.of(HeaderVerification.Mode.ERROR)),
        not(
            equalTo(
                getRuleKey(
                    HeaderVerification.of(
                        HeaderVerification.Mode.ERROR,
                        ImmutableSortedSet.of(".*"),
                        ImmutableSortedSet.of())))));
  }
}
