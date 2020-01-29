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

package com.facebook.buck.core.rules.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.SourceArtifactImpl;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgsFactory;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.providers.lib.ImmutableRunInfo;
import com.facebook.buck.core.rules.providers.lib.RunInfo;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import org.junit.Rule;
import org.junit.Test;

public class RunInfoLegacyToolTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void stringifiesArgs() {
    DefaultProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    Path path = Paths.get("some_file");

    ImmutableMap<String, String> env = ImmutableMap.of("FOO", "foo_value");
    Artifact artifact = SourceArtifactImpl.of(PathSourcePath.of(filesystem, path));
    RunInfo runInfo =
        new ImmutableRunInfo(
            env, CommandLineArgsFactory.from(ImmutableList.of(artifact, "--foo", "--bar")));
    RunInfoLegacyTool tool = RunInfoLegacyTool.of(runInfo);

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    ImmutableList<String> expected =
        ImmutableList.of(filesystem.resolve(path).toAbsolutePath().toString(), "--foo", "--bar");

    assertEquals(expected, tool.getCommandPrefix(graphBuilder.getSourcePathResolver()));
    assertEquals(env, tool.getEnvironment(graphBuilder.getSourcePathResolver()));
  }

  @Test
  public void affectsRuleKey() {
    DefaultProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    HashMap<Path, HashCode> hashes = new HashMap<>();
    hashes.put(filesystem.resolve("some_file"), HashCode.fromString("aaaa"));
    FakeFileHashCache hashCache = new FakeFileHashCache(hashes);

    TestDefaultRuleKeyFactory ruleKeyFactory = new TestDefaultRuleKeyFactory(hashCache, ruleFinder);

    Path path = Paths.get("some_file");

    RunInfo runInfo1 =
        new ImmutableRunInfo(
            ImmutableMap.of("FOO", "foo_value"),
            CommandLineArgsFactory.from(
                ImmutableList.of(
                    SourceArtifactImpl.of(PathSourcePath.of(filesystem, path)), "--foo", "--bar")));
    RunInfo runInfo2 =
        new ImmutableRunInfo(
            ImmutableMap.of("FOO", "foo_value"),
            CommandLineArgsFactory.from(
                ImmutableList.of(
                    SourceArtifactImpl.of(PathSourcePath.of(filesystem, path)), "--foo", "--bar")));
    RunInfo runInfo3 =
        new ImmutableRunInfo(
            ImmutableMap.of("BAR", "bar_value"),
            CommandLineArgsFactory.from(
                ImmutableList.of(
                    SourceArtifactImpl.of(PathSourcePath.of(filesystem, path)), "--foo", "--bar")));
    RunInfo runInfo4 =
        new ImmutableRunInfo(
            ImmutableMap.of("FOO", "different_env"),
            CommandLineArgsFactory.from(
                ImmutableList.of(
                    SourceArtifactImpl.of(PathSourcePath.of(filesystem, path)), "--foo", "--bar")));

    RunInfoLegacyTool tool1 = RunInfoLegacyTool.of(runInfo1);
    RunInfoLegacyTool tool2 = RunInfoLegacyTool.of(runInfo2);
    RunInfoLegacyTool tool3 = RunInfoLegacyTool.of(runInfo3);
    RunInfoLegacyTool tool4 = RunInfoLegacyTool.of(runInfo4);

    HashCode ruleKey1 =
        ruleKeyFactory
            .newBuilderForTesting(new FakeBuildRule(target))
            .setReflectively("field", tool1)
            .build();
    HashCode ruleKey2 =
        ruleKeyFactory
            .newBuilderForTesting(new FakeBuildRule(target))
            .setReflectively("field", tool2)
            .build();
    HashCode ruleKey3 =
        ruleKeyFactory
            .newBuilderForTesting(new FakeBuildRule(target))
            .setReflectively("field", tool3)
            .build();

    hashCache.set(filesystem.resolve(path), HashCode.fromString("bbbb"));
    HashCode ruleKey4 =
        ruleKeyFactory
            .newBuilderForTesting(new FakeBuildRule(target))
            .setReflectively("field", tool4)
            .build();

    assertEquals(ruleKey1, ruleKey2);
    assertNotEquals(ruleKey1, ruleKey3);
    assertNotEquals(ruleKey1, ruleKey4);
  }
}
