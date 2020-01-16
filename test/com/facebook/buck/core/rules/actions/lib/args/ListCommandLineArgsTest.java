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
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.actions.ActionRegistryForTests;
import com.facebook.buck.core.rules.actions.lib.WriteAction;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.EvalException;
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
  public void returnsProperStreamAndSize() throws EvalException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    Artifact path1 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("some_bin")));
    Artifact path2 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("other_file")));
    BuildTarget target = BuildTargetFactory.newInstance("//:some_rule");
    ActionRegistryForTests registry = new ActionRegistryForTests(target, filesystem);
    Artifact artifact3 = registry.declareArtifact(Paths.get("out.txt"), Location.BUILTIN);
    OutputArtifact artifact3Output = (OutputArtifact) artifact3.asOutputArtifact(Location.BUILTIN);
    Path artifact3Path = BuildPaths.getGenDir(filesystem, target).resolve("out.txt");

    CommandLineArgs args =
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2, artifact3Output),
            CommandLineArgs.DEFAULT_FORMAT_STRING);

    new WriteAction(
        registry, ImmutableSortedSet.of(), ImmutableSortedSet.of(artifact3), "contents", false);

    assertEquals(
        ImmutableList.of(
            filesystem.resolve("some_bin").toAbsolutePath().toString(),
            "1",
            "foo",
            "other_file",
            artifact3Path.toString()),
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(filesystem))
            .build(args)
            .getCommandLineArgs());
    assertEquals(5, args.getEstimatedArgsCount());

    ImmutableSortedSet.Builder<Artifact> inputs = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<Artifact> outputs = ImmutableSortedSet.naturalOrder();
    args.visitInputsAndOutputs(inputs::add, outputs::add);

    assertEquals(ImmutableSortedSet.of(path1, path2), inputs.build());
    assertEquals(ImmutableSortedSet.of(artifact3), outputs.build());
  }

  @Test
  public void formatsStrings() throws EvalException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    Artifact path1 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("some_bin")));
    Artifact path2 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("other_file")));
    BuildTarget target = BuildTargetFactory.newInstance("//:some_rule");
    ActionRegistryForTests registry = new ActionRegistryForTests(target, filesystem);
    Artifact artifact3 = registry.declareArtifact(Paths.get("out.txt"), Location.BUILTIN);
    OutputArtifact artifact3Output = (OutputArtifact) artifact3.asOutputArtifact(Location.BUILTIN);
    Path artifact3Path = BuildPaths.getGenDir(filesystem, target).resolve("out.txt");

    CommandLineArgs args =
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2, artifact3Output), "--prefix=%s");

    new WriteAction(
        registry, ImmutableSortedSet.of(), ImmutableSortedSet.of(artifact3), "contents", false);

    assertEquals(
        ImmutableList.of(
            "--prefix=" + filesystem.resolve("some_bin").toAbsolutePath().toString(),
            "--prefix=1",
            "--prefix=foo",
            "--prefix=other_file",
            "--prefix=" + artifact3Path.toString()),
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(filesystem))
            .build(args)
            .getCommandLineArgs());

    assertEquals(
        ImmutableList.of(
            "foo=foo", String.format("%s=%s", artifact3Path.toString(), artifact3Path.toString())),
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(filesystem))
            .build(new ListCommandLineArgs(ImmutableList.of("foo", artifact3Output), "%s=%s"))
            .getCommandLineArgs());

    assertEquals(5, args.getEstimatedArgsCount());

    ImmutableSortedSet.Builder<Artifact> inputs = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<Artifact> outputs = ImmutableSortedSet.naturalOrder();
    args.visitInputsAndOutputs(inputs::add, outputs::add);

    assertEquals(ImmutableSortedSet.of(path1, path2), inputs.build());
    assertEquals(ImmutableSortedSet.of(artifact3), outputs.build());
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
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2, path3), CommandLineArgs.DEFAULT_FORMAT_STRING);
    ListCommandLineArgs listArgs2 =
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2, path3), CommandLineArgs.DEFAULT_FORMAT_STRING);
    ListCommandLineArgs listArgs3 =
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2, path3), CommandLineArgs.DEFAULT_FORMAT_STRING);
    ListCommandLineArgs listArgs4 =
        new ListCommandLineArgs(ImmutableList.of(path1, 1, "foo", path2, path3), "foo_%s");

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
    HashCode ruleKey4 =
        ruleKeyFactory
            .newBuilderForTesting(new FakeBuildRule(target))
            .setReflectively("field", listArgs4)
            .build();

    assertEquals(ruleKey1, ruleKey2);
    assertNotEquals(ruleKey1, ruleKey3);
    assertNotEquals(ruleKey3, ruleKey4);
  }
}
