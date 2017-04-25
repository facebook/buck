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

package com.facebook.buck.rules.args;

import static org.junit.Assert.assertThat;

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
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
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import org.hamcrest.Matchers;
import org.junit.Test;

public class SanitizedArgTest {

  private RuleKeyBuilder<HashCode> createRuleKeyBuilder() {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    FileHashCache fileHashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(projectFilesystem)));
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    return new UncachedRuleKeyBuilder(
        ruleFinder,
        resolver,
        fileHashCache,
        new DefaultRuleKeyFactory(0, fileHashCache, resolver, ruleFinder));
  }

  @Test
  public void stringify() {
    SourcePathResolver pathResolver =
        new SourcePathResolver(
            new SourcePathRuleFinder(
                new BuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));

    SanitizedArg arg = new SanitizedArg(Functions.constant("sanitized"), "unsanitized");
    assertThat(Arg.stringifyList(arg, pathResolver), Matchers.contains("unsanitized"));
  }

  @Test
  public void appendToRuleKey() {
    SanitizedArg arg1 = new SanitizedArg(Functions.constant("sanitized"), "unsanitized 1");
    SanitizedArg arg2 = new SanitizedArg(Functions.constant("sanitized"), "unsanitized 2");
    RuleKeyBuilder<HashCode> builder1 = createRuleKeyBuilder();
    RuleKeyBuilder<HashCode> builder2 = createRuleKeyBuilder();
    arg1.appendToRuleKey(builder1);
    arg2.appendToRuleKey(builder2);
    assertThat(builder1.build(), Matchers.equalTo(builder2.build()));
  }
}
