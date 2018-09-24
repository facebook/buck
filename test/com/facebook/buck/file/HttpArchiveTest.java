/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.file;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HttpArchiveTest {

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = new FakeProjectFilesystem();
  }

  // Helper method to get all of our deps setup just so we can do some quick rulekey modification
  // tests
  private RuleKey getRuleKey(String out, ArchiveFormat format, Optional<String> stripPrefix) {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params =
        new BuildRuleParams(
            ImmutableSortedSet::of, ImmutableSortedSet::of, ImmutableSortedSet.of());

    HttpFile httpFile =
        new HttpFile(
            target.withAppendedFlavors(InternalFlavor.of("archive-download")),
            filesystem,
            params,
            (eventBus, path, output) -> false,
            ImmutableList.of(URI.create("http://example.com/foo.zip")),
            HashCode.fromString("d29acd2e2a5bc00e04c85a44c3ca7106c51dc0d2488f8222b07179d567a7f128"),
            out,
            false);

    HttpArchive httpArchive =
        new HttpArchive(
            target, filesystem, params, httpFile, out, format, stripPrefix.map(Paths::get));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeFileHashCache hashCache = FakeFileHashCache.createFromStrings(ImmutableMap.of());
    return new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder).build(httpArchive);
  }

  @Test
  public void ruleKeyIsDeterministic() {
    RuleKey originalKey = getRuleKey("foo", ArchiveFormat.TAR, Optional.of("foo-1.2.3"));
    for (int i = 0; i < 20; i++) {
      Assert.assertEquals(
          originalKey, getRuleKey("foo", ArchiveFormat.TAR, Optional.of("foo-1.2.3")));
    }
  }

  @Test
  public void outputAffectRuleKey() {
    RuleKey originalKey = getRuleKey("foo", ArchiveFormat.TAR, Optional.of("foo-1.2.3"));
    RuleKey changedKey = getRuleKey("foo.bar", ArchiveFormat.TAR, Optional.of("foo-1.2.3"));

    Assert.assertNotEquals(originalKey, changedKey);
  }

  @Test
  public void formatAffectRuleKey() {
    RuleKey originalKey = getRuleKey("foo", ArchiveFormat.TAR, Optional.of("foo-1.2.3"));
    RuleKey changedKey = getRuleKey("foo", ArchiveFormat.ZIP, Optional.of("foo-1.2.3"));

    Assert.assertNotEquals(originalKey, changedKey);
  }

  @Test
  public void stripPrefixAffectRuleKey() {
    RuleKey originalKey = getRuleKey("foo", ArchiveFormat.TAR, Optional.of("foo-1.2.3"));
    RuleKey changedKey1 = getRuleKey("foo", ArchiveFormat.TAR, Optional.of("bar-2.3.4"));
    RuleKey changedKey2 = getRuleKey("foo", ArchiveFormat.TAR, Optional.empty());

    Assert.assertNotEquals(originalKey, changedKey1);
    Assert.assertNotEquals(originalKey, changedKey2);
  }
}
