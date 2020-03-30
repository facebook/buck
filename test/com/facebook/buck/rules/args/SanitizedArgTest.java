/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.rules.args;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.AlterRuleKeys;
import com.facebook.buck.rules.keys.RuleKeyBuilder;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.UncachedRuleKeyBuilder;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import org.hamcrest.Matchers;
import org.junit.Test;

public class SanitizedArgTest {

  private RuleKeyBuilder<HashCode> createRuleKeyBuilder() {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    FileHashLoader fileHashLoader =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    projectFilesystem, FileHashCacheMode.DEFAULT)));
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    return new UncachedRuleKeyBuilder(
        ruleFinder, fileHashLoader, new TestDefaultRuleKeyFactory(fileHashLoader, ruleFinder));
  }

  private void amendKeyList(
      RuleKeyBuilder<HashCode> builder, ImmutableList<? extends AddsToRuleKey> values) {
    for (AddsToRuleKey value : values) {
      AlterRuleKeys.amendKey(builder, value);
    }
  }

  @Test
  public void stringify() {
    SourcePathResolverAdapter pathResolver = new TestActionGraphBuilder().getSourcePathResolver();

    SanitizedArg arg = SanitizedArg.create(Functions.constant("sanitized"), "unsanitized");
    assertThat(Arg.stringifyList(arg, pathResolver), Matchers.contains("unsanitized"));
  }

  @Test
  public void appendToRuleKey() {
    SanitizedArg arg1 = SanitizedArg.create(Functions.constant("sanitized"), "unsanitized 1");
    SanitizedArg arg2 = SanitizedArg.create(Functions.constant("sanitized"), "unsanitized 2");
    RuleKeyBuilder<HashCode> builder1 = createRuleKeyBuilder();
    RuleKeyBuilder<HashCode> builder2 = createRuleKeyBuilder();
    AlterRuleKeys.amendKey(builder1, arg1);
    AlterRuleKeys.amendKey(builder2, arg2);
    assertThat(builder1.build(), Matchers.equalTo(builder2.build()));
  }

  @Test
  public void sanitizedArgsList() {
    ImmutableList<Arg> args1 =
        SanitizedArg.from(Functions.constant("sanitized"), ImmutableList.of("", "unsanitized 1"));
    ImmutableList<Arg> args2 =
        SanitizedArg.from(Functions.constant("sanitized"), ImmutableList.of("unsanitized 2"));

    RuleKeyBuilder<HashCode> builder1 = createRuleKeyBuilder();
    RuleKeyBuilder<HashCode> builder2 = createRuleKeyBuilder();
    amendKeyList(builder1, args1);
    amendKeyList(builder2, args2);
    assertThat(builder1.build(), Matchers.equalTo(builder2.build()));
  }
}
