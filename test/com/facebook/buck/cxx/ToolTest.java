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

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

public class ToolTest {

  private RuleKey.Builder createRuleKeyBuilder(
      RuleKeyBuilderFactory factory,
      SourcePathResolver resolver) {
    return factory.newInstance(new FakeBuildRule("//:test", resolver), resolver);
  }

  @Test
  public void sourcePathVersion() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new FakeRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.<String, String>builder()
                    .put("path", Strings.repeat("a", 40))
                    .build()));

    SourcePath path = new TestSourcePath("path");
    RuleKey.Builder.RuleKeyPair pathRuleKey =
        createRuleKeyBuilder(ruleKeyBuilderFactory, pathResolver)
            .setReflectively("tool", path)
            .build();

    Tool tool1 = new SourcePathTool(path);
    RuleKey.Builder.RuleKeyPair tool1RuleKey =
        createRuleKeyBuilder(ruleKeyBuilderFactory, pathResolver)
            .setReflectively("tool", tool1)
            .build();
    assertEquals(pathRuleKey, tool1RuleKey);

    Tool tool2 = new SourcePathTool(path);
    RuleKey.Builder.RuleKeyPair tool2RuleKey =
        createRuleKeyBuilder(ruleKeyBuilderFactory, pathResolver)
            .setReflectively("tool", tool2)
            .build();
    assertEquals(pathRuleKey, tool2RuleKey);
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
    RuleKey.Builder.RuleKeyPair versionRuleKey =
        createRuleKeyBuilder(ruleKeyBuilderFactory, pathResolver)
            .setReflectively("tool", String.format("%s (%s)", tool, version))
            .build();

    Tool tool1 =
        new VersionedTool(
            new TestSourcePath("something"),
            ImmutableList.<String>of(),
            tool,
            version);
    RuleKey.Builder.RuleKeyPair tool1RuleKey =
        createRuleKeyBuilder(ruleKeyBuilderFactory, pathResolver)
            .setReflectively("tool", tool1)
            .build();
    assertEquals(versionRuleKey, tool1RuleKey);

    Tool tool2 =
        new VersionedTool(
            new TestSourcePath("something-else"),
            ImmutableList.<String>of(),
            tool,
            version);
    RuleKey.Builder.RuleKeyPair tool2RuleKey =
        createRuleKeyBuilder(ruleKeyBuilderFactory, pathResolver)
            .setReflectively("tool", tool2)
            .build();
    assertEquals(versionRuleKey, tool2RuleKey);
  }

}
