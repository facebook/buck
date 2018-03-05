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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class ToolTest {

  @Test
  public void hashFileToolsCreatedWithTheSamePathAreEqual() {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestBuildRuleResolver());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    DefaultRuleKeyFactory ruleKeyFactory =
        new TestDefaultRuleKeyFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("path", Strings.repeat("a", 40))
                    .put("other-path", Strings.repeat("b", 40))
                    .put("same", Strings.repeat("a", 40))
                    .build()),
            pathResolver,
            ruleFinder);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    Path path = Paths.get("path");
    Path otherPath = Paths.get("other-path");
    Path same = Paths.get("same");

    Tool tool1 = new HashedFileTool(PathSourcePath.of(filesystem, path));
    RuleKey tool1RuleKey =
        createRuleKeyBuilder(ruleKeyFactory).setReflectively("tool", tool1).build(RuleKey::new);

    Tool tool2 = new HashedFileTool(PathSourcePath.of(filesystem, path));
    RuleKey tool2RuleKey =
        createRuleKeyBuilder(ruleKeyFactory).setReflectively("tool", tool2).build(RuleKey::new);

    // Same name, same sha1
    assertEquals(tool1RuleKey, tool2RuleKey);

    Tool tool3 = new HashedFileTool(PathSourcePath.of(filesystem, otherPath));
    RuleKey tool3RuleKey =
        createRuleKeyBuilder(ruleKeyFactory).setReflectively("tool", tool3).build(RuleKey::new);

    // Different name, different sha1
    assertNotEquals(tool1RuleKey, tool3RuleKey);

    Tool tool4 = new HashedFileTool(PathSourcePath.of(filesystem, same));
    RuleKey tool4RuleKey =
        createRuleKeyBuilder(ruleKeyFactory).setReflectively("tool", tool4).build(RuleKey::new);

    // Different name, same sha1
    assertNotEquals(tool1RuleKey, tool4RuleKey);
  }

  @Test
  public void customVersion() {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestBuildRuleResolver());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    DefaultRuleKeyFactory ruleKeyFactory =
        new TestDefaultRuleKeyFactory(
            FakeFileHashCache.createFromStrings(ImmutableMap.of()), pathResolver, ruleFinder);

    String tool = "tool";
    String version = "version";

    Tool tool1 = VersionedTool.of(Paths.get("something"), tool, version);
    RuleKey tool1RuleKey =
        createRuleKeyBuilder(ruleKeyFactory).setReflectively("tool", tool1).build(RuleKey::new);

    Tool tool2 = VersionedTool.of(Paths.get("something-else"), tool, version);
    RuleKey tool2RuleKey =
        createRuleKeyBuilder(ruleKeyFactory).setReflectively("tool", tool2).build(RuleKey::new);

    assertEquals(tool1RuleKey, tool2RuleKey);
  }

  @Test
  public void shouldAssumeThatToolsInDifferentAbsoluteLocationsWithTheSameNameAreTheSame() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    // Note: both file names are the same
    HashedFileTool tool1 =
        new HashedFileTool(PathSourcePath.of(filesystem, Paths.get("/usr/local/bin/python2.7")));
    HashedFileTool tool2 =
        new HashedFileTool(PathSourcePath.of(filesystem, Paths.get("/usr/local/bin/python2.7")));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestBuildRuleResolver());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    DefaultRuleKeyFactory ruleKeyFactory =
        new TestDefaultRuleKeyFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    // Note: the hashes of both files are the same
                    .put("/usr/local/bin/python2.7", Strings.repeat("a", 40))
                    .put("/opt/bin/python2.7", Strings.repeat("a", 40))
                    .build()),
            pathResolver,
            ruleFinder);

    RuleKey tool1RuleKey =
        createRuleKeyBuilder(ruleKeyFactory).setReflectively("tool", tool1).build(RuleKey::new);

    RuleKey tool2RuleKey =
        createRuleKeyBuilder(ruleKeyFactory).setReflectively("tool", tool2).build(RuleKey::new);

    assertEquals(tool1RuleKey, tool2RuleKey);
  }

  private DefaultRuleKeyFactory.Builder<HashCode> createRuleKeyBuilder(
      DefaultRuleKeyFactory factory) {
    return factory.newBuilderForTesting(new FakeBuildRule("//:test"));
  }
}
