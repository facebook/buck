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

package com.facebook.buck.core.rules.actions.lib.args;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.artifact.BuildArtifactFactoryForTests;
import com.facebook.buck.core.artifact.ImmutableSourceArtifactImpl;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.events.Location;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ListCommandLineArgsTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void returnsProperStreamAndSize() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Artifact path1 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("some_bin")));
    Artifact path2 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("other_file")));
    CommandLineArgs args = new ListCommandLineArgs(ImmutableList.of(path1, 1, "foo", path2));

    assertEquals(
        ImmutableList.of(
            filesystem.resolve("some_bin").toAbsolutePath().toString(), "1", "foo", "other_file"),
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(filesystem))
            .build(args)
            .getCommandLineArgs());
    assertEquals(4, args.getEstimatedArgsCount());
  }

  @Test
  public void ruleKeyChangesOnChanges() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    HashMap<Path, HashCode> hashes = new HashMap<>();
    hashes.put(filesystem.resolve("some_bin"), HashCode.fromString("aaaa"));
    hashes.put(filesystem.resolve("other_file"), HashCode.fromString("bbbb"));

    FakeFileHashCache hashCache = new FakeFileHashCache(hashes);
    DefaultRuleKeyFactory ruleKeyFactory = new TestDefaultRuleKeyFactory(hashCache, ruleFinder);

    Artifact path1 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("some_bin")));
    Artifact path2 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("other_file")));
    /**
     * Make sure that we have a build target source path. This tests that we don't run into problems
     * with infinite recursion. See {@link CommandLineArgs} for details
     */
    Artifact path3 =
        new BuildArtifactFactoryForTests(target, filesystem)
            .createBuildArtifact(Paths.get("other_file_2"), Location.BUILTIN);
    ListCommandLineArgs listArgs1 =
        new ListCommandLineArgs(ImmutableList.of(path1, 1, "foo", path2, path3));
    ListCommandLineArgs listArgs2 =
        new ListCommandLineArgs(ImmutableList.of(path1, 1, "foo", path2, path3));
    ListCommandLineArgs listArgs3 =
        new ListCommandLineArgs(ImmutableList.of(path1, 1, "foo", path2, path3));

    HashCode ruleKey1 =
        ruleKeyFactory
            .newBuilderForTesting(new FakeBuildRule(target))
            .setReflectively("field", listArgs1)
            .build();
    HashCode ruleKey2 =
        ruleKeyFactory
            .newBuilderForTesting(new FakeBuildRule(target))
            .setReflectively("field", listArgs2)
            .build();

    hashCache.set(filesystem.resolve("some_bin"), HashCode.fromString("cccc"));
    HashCode ruleKey3 =
        ruleKeyFactory
            .newBuilderForTesting(new FakeBuildRule(target))
            .setReflectively("field", listArgs3)
            .build();

    assertEquals(ruleKey1, ruleKey2);
    assertNotEquals(ruleKey1, ruleKey3);
  }
}
