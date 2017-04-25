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

import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.UncachedRuleKeyBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxHeadersDirTest {

  private RuleKey getRuleKey(ProjectFilesystem filesystem, CxxHeaders cxxHeaders) {
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    FileHashCache fileHashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(0, fileHashCache, pathResolver, ruleFinder);
    UncachedRuleKeyBuilder builder =
        new UncachedRuleKeyBuilder(ruleFinder, pathResolver, fileHashCache, factory);
    cxxHeaders.appendToRuleKey(builder);
    return builder.build(RuleKey::new);
  }

  @Test
  public void dirContentsAffectsRuleKey() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path headerDir = filesystem.getPath("foo");
    filesystem.mkdirs(headerDir);
    CxxHeadersDir cxxHeaders =
        CxxHeadersDir.of(
            CxxPreprocessables.IncludeType.SYSTEM, new PathSourcePath(filesystem, headerDir));
    filesystem.writeContentsToPath("something", headerDir.resolve("bar.h"));
    RuleKey ruleKey1 = getRuleKey(filesystem, cxxHeaders);
    filesystem.writeContentsToPath("something else", headerDir.resolve("bar.h"));
    RuleKey ruleKey2 = getRuleKey(filesystem, cxxHeaders);
    assertThat(ruleKey1, Matchers.not(Matchers.equalTo(ruleKey2)));
  }

  @Test
  public void typeAffectsRuleKey() throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path headerDir = filesystem.getPath("foo");
    filesystem.mkdirs(headerDir);
    RuleKey ruleKey1 =
        getRuleKey(
            filesystem,
            CxxHeadersDir.of(
                CxxPreprocessables.IncludeType.LOCAL, new PathSourcePath(filesystem, headerDir)));
    RuleKey ruleKey2 =
        getRuleKey(
            filesystem,
            CxxHeadersDir.of(
                CxxPreprocessables.IncludeType.SYSTEM, new PathSourcePath(filesystem, headerDir)));
    assertThat(ruleKey1, Matchers.not(Matchers.equalTo(ruleKey2)));
  }
}
