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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ToolTest {

  @Test
  public void hashFileToolsCreatedWithTheSamePathAreEqual() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new FakeRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("path", Strings.repeat("a", 40))
                    .put("other-path", Strings.repeat("b", 40))
                    .put("same", Strings.repeat("a", 40))
                    .build()));

    Path path = Paths.get("path");
    Path otherPath = Paths.get("other-path");
    Path same = Paths.get("same");

    Tool tool1 = new HashedFileTool(path);
    RuleKey.Builder.RuleKeyPair tool1RuleKey =
        createRuleKeyBuilder(ruleKeyBuilderFactory, pathResolver)
            .setReflectively("tool", tool1)
            .build();

    Tool tool2 = new HashedFileTool(path);
    RuleKey.Builder.RuleKeyPair tool2RuleKey =
        createRuleKeyBuilder(ruleKeyBuilderFactory, pathResolver)
            .setReflectively("tool", tool2)
            .build();

    // Same name, same sha1
    assertEquals(tool1RuleKey, tool2RuleKey);

    Tool tool3 = new HashedFileTool(otherPath);
    RuleKey.Builder.RuleKeyPair tool3RuleKey =
        createRuleKeyBuilder(ruleKeyBuilderFactory, pathResolver)
            .setReflectively("tool", tool3)
            .build();

    // Different name, different sha1
    assertNotEquals(tool1RuleKey, tool3RuleKey);

    Tool tool4 = new HashedFileTool(same);
    RuleKey.Builder.RuleKeyPair tool4RuleKey =
        createRuleKeyBuilder(ruleKeyBuilderFactory, pathResolver)
            .setReflectively("tool", tool4)
            .build();

    // Different name, same sha1
    assertNotEquals(tool1RuleKey, tool4RuleKey);
  }

  @Test
  public void customVersion() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new FakeRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>of()));

    String tool = "tool";
    String version = "version";

    Tool tool1 =
        new VersionedTool(
            Paths.get("something"),
            ImmutableList.<String>of(),
            tool,
            version);
    RuleKey.Builder.RuleKeyPair tool1RuleKey =
        createRuleKeyBuilder(ruleKeyBuilderFactory, pathResolver)
            .setReflectively("tool", tool1)
            .build();

    Tool tool2 =
        new VersionedTool(
            Paths.get("something-else"),
            ImmutableList.<String>of(),
            tool,
            version);
    RuleKey.Builder.RuleKeyPair tool2RuleKey =
        createRuleKeyBuilder(ruleKeyBuilderFactory, pathResolver)
            .setReflectively("tool", tool2)
            .build();

    assertEquals(tool1RuleKey, tool2RuleKey);
  }

  @Test
  public void shouldAssumeThatToolsInDifferentAbsoluteLocationsWithTheSameNameAreTheSame() {
    // Note: both file names are the same
    HashedFileTool tool1 = new HashedFileTool(Paths.get("/usr/local/bin/python2.7"));
    HashedFileTool tool2 = new HashedFileTool(Paths.get("/opt/bin/python2.7"));

    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new FakeRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    // Note: the hashes of both files are the same
                    .put("/usr/local/bin/python2.7", Strings.repeat("a", 40))
                    .put("/opt/bin/python2.7", Strings.repeat("a", 40))
                    .build()));

    RuleKey.Builder.RuleKeyPair tool1RuleKey =
        createRuleKeyBuilder(ruleKeyBuilderFactory, pathResolver)
            .setReflectively("tool", tool1)
            .build();

    RuleKey.Builder.RuleKeyPair tool2RuleKey =
        createRuleKeyBuilder(ruleKeyBuilderFactory, pathResolver)
            .setReflectively("tool", tool2)
            .build();

    assertEquals(tool1RuleKey, tool2RuleKey);
  }

  private RuleKey.Builder createRuleKeyBuilder(
      RuleKeyBuilderFactory factory,
      SourcePathResolver resolver) {
    return factory.newInstance(new FakeBuildRule("//:test", resolver), resolver);
  }
}
