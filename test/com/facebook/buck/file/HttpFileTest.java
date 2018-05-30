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

import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.net.URI;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class HttpFileTest {

  public @Rule TemporaryPaths temporaryDir = new TemporaryPaths();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(temporaryDir.getRoot());
  }

  @Test
  public void ruleKeyIsDeterministic() {
    RuleKey originalKey =
        getRuleKey(
            ImmutableList.of("https://example.com/1.exe"),
            "2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca",
            true,
            "foo.exe");
    for (int i = 0; i < 20; i++) {
      Assert.assertEquals(
          originalKey,
          getRuleKey(
              ImmutableList.of("https://example.com/1.exe"),
              "2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca",
              true,
              "foo.exe"));
    }
  }

  @Test
  public void urisAffectRuleKey() {
    RuleKey originalKey =
        getRuleKey(
            ImmutableList.of("https://example.com/1.exe"),
            "2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca",
            true,
            "foo.exe");
    RuleKey changedKey1 =
        getRuleKey(
            ImmutableList.of(),
            "2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca",
            true,
            "foo.exe");
    RuleKey changedKey2 =
        getRuleKey(
            ImmutableList.of("https://example.com/1.exe", "https://example.com/2.exe"),
            "2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca",
            true,
            "foo.exe");

    Assert.assertNotEquals(originalKey, changedKey1);
    Assert.assertNotEquals(originalKey, changedKey2);
  }

  @Test
  public void sha256AffectsRuleKey() {
    RuleKey originalKey =
        getRuleKey(
            ImmutableList.of("https://example.com/1.exe"),
            "2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca",
            true,
            "foo.exe");
    RuleKey changedKey =
        getRuleKey(
            ImmutableList.of("https://example.com/1.exe"),
            "534be6d331e8f1ab7892f19e8fe23db4907bdc54f517a8b22adc82e69b6b1093",
            true,
            "foo.exe");

    Assert.assertNotEquals(originalKey, changedKey);
  }

  @Test
  public void outputAffectRuleKey() {
    RuleKey originalKey =
        getRuleKey(
            ImmutableList.of("https://example.com/1.exe"),
            "2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca",
            true,
            "foo.exe");
    RuleKey changedKey =
        getRuleKey(
            ImmutableList.of("https://example.com/1.exe"),
            "2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca",
            false,
            "foo.exe");

    Assert.assertNotEquals(originalKey, changedKey);
  }

  @Test
  public void executableAffectRuleKey() {
    RuleKey originalKey =
        getRuleKey(
            ImmutableList.of("https://example.com/1.exe"),
            "2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca",
            true,
            "foo.exe");
    RuleKey changedKey =
        getRuleKey(
            ImmutableList.of("https://example.com/1.exe"),
            "2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca",
            true,
            "foo.bar.exe");

    Assert.assertNotEquals(originalKey, changedKey);
  }

  private RuleKey getRuleKey(
      ImmutableList<String> uris, String sha256, boolean executable, String out) {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params =
        new BuildRuleParams(
            ImmutableSortedSet::of, ImmutableSortedSet::of, ImmutableSortedSet.of());

    HttpFile httpFile =
        new HttpFile(
            target,
            filesystem,
            params,
            (eventBus, path, output) -> false,
            uris.stream().map(URI::create).collect(ImmutableList.toImmutableList()),
            HashCode.fromString(sha256),
            out,
            executable);

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeFileHashCache hashCache = FakeFileHashCache.createFromStrings(ImmutableMap.of());
    return new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder).build(httpFile);
  }
}
