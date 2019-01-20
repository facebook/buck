/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.shell;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.UncachedRuleKeyBuilder;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.Function;
import org.junit.Rule;
import org.junit.Test;

public class CommandAliasIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void invokesNestedToolWithSubPath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "command_alias", tmp).setUp();

    String stdout =
        workspace.runBuckCommand("run", "//:outer", "extra arg").assertSuccess().getStdout();

    assertEquals(
        String.join(
            System.lineSeparator(),
            "BUCK",
            "echo.bat",
            "echo.sh",
            "1",
            "second arg",
            "extra arg",
            "1 2",
            ""),
        stdout);
  }

  @Test
  public void onlyBuildsToolForCurrentPlatform() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "command_alias", tmp).setUp();

    workspace.runBuckCommand("run", "//:platform_specific").assertSuccess();
    File genDir = workspace.getPath(workspace.getBuckPaths().getGenDir()).toFile();
    ImmutableSet<String> platforms =
        Arrays.stream(Platform.values())
            .map(Enum::name)
            .map(String::toLowerCase)
            .collect(ImmutableSet.toImmutableSet());
    String[] files = genDir.list((dir, name) -> platforms.contains(name));
    assertEquals(
        ImmutableList.of(Platform.detect().name().toLowerCase()), ImmutableList.copyOf(files));
  }

  @Test
  public void genruleUsingCommandAliasInExeMacroHasStableRuleKey() {
    Function<Platform, RuleKey> genruleKey =
        platform -> {
          CommandAliasBuilder builder =
              new CommandAliasBuilder(
                  BuildTargetFactory.newInstance("//:command-alias"),
                  new CommandAliasDescription(platform));

          BuildTarget genruleTarget = BuildTargetFactory.newInstance("//:genrule");
          CommandAliasBuilder.BuildResult result =
              builder
                  .setPlatformExe(
                      ImmutableMap.of(
                          Platform.WINDOWS,
                          BuildTargetFactory.newInstance("//:windows"),
                          Platform.LINUX,
                          BuildTargetFactory.newInstance("//:linux")))
                  .addTarget(
                      GenruleBuilder.newGenruleBuilder(genruleTarget)
                          .setCmd("$(exe //:command-alias) 1 2 3")
                          .setOut("out")
                          .build())
                  .buildResult();

          return ruleKey(result, genruleTarget);
        };

    assertEquals(genruleKey.apply(Platform.WINDOWS), genruleKey.apply(Platform.LINUX));
    assertEquals(genruleKey.apply(Platform.LINUX), genruleKey.apply(Platform.UNKNOWN));
  }

  private static RuleKey ruleKey(CommandAliasBuilder.BuildResult result, BuildTarget target) {
    SourcePathResolver pathResolver = result.sourcePathResolver();
    SourcePathRuleFinder ruleFinder = result.ruleFinder();
    FakeFileHashCache hashCache = FakeFileHashCache.createFromStrings(ImmutableMap.of());
    return new UncachedRuleKeyBuilder(
            ruleFinder,
            pathResolver,
            hashCache,
            new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder))
        .setReflectively("key", result.graphBuilder().requireRule(target))
        .build(RuleKey::new);
  }
}
