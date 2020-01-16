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
import com.facebook.buck.core.rules.providers.lib.ImmutableRunInfo;
import com.facebook.buck.core.rules.providers.lib.RunInfo;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.EvalException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import org.junit.Test;

public class AggregateCommandLineArgsTest {

  private static RunInfo createRunInfo(
      ImmutableSortedMap<String, String> env, ImmutableList<Object> args) {
    return new ImmutableRunInfo(
        env, new ListCommandLineArgs(args, CommandLineArgs.DEFAULT_FORMAT_STRING));
  }

  @Test
  public void returnsProperStreamAndArgCount() throws EvalException {
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
        new AggregateCommandLineArgs(
            ImmutableList.of(
                createRunInfo(ImmutableSortedMap.of("FOO", "foo_val"), ImmutableList.of(path1)),
                createRunInfo(ImmutableSortedMap.of("BAZ", "baz_val"), ImmutableList.of(path2, 1)),
                CommandLineArgsFactory.from(ImmutableList.of("foo", "bar", artifact3Output))));

    new WriteAction(
        registry, ImmutableSortedSet.of(), ImmutableSortedSet.of(artifact3), "contents", false);

    CommandLine cli =
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(filesystem)).build(args);

    assertEquals(
        ImmutableList.of(
            filesystem.resolve("some_bin").toAbsolutePath().toString(),
            "other_file",
            "1",
            "foo",
            "bar",
            artifact3Path.toString()),
        cli.getCommandLineArgs());
    assertEquals(6, args.getEstimatedArgsCount());
    assertEquals(
        ImmutableSortedMap.of("FOO", "foo_val", "BAZ", "baz_val"), cli.getEnvironmentVariables());

    ImmutableSortedSet.Builder<Artifact> inputs = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<Artifact> outputs = ImmutableSortedSet.naturalOrder();
    args.visitInputsAndOutputs(inputs::add, outputs::add);

    assertEquals(ImmutableSortedSet.of(path1, path2), inputs.build());
    assertEquals(ImmutableSortedSet.of(artifact3), outputs.build());
  }

  @Test
  public void formatsStrings() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Artifact path1 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("in1.txt")));
    Artifact path2 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("in2.txt")));
    Artifact path3 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("in3.txt")));
    Artifact path4 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("in4.txt")));

    CommandLineArgs args =
        new AggregateCommandLineArgs(
            ImmutableList.of(
                CommandLineArgsFactory.from(ImmutableList.of("foo", "bar")),
                CommandLineArgsFactory.from(ImmutableList.of(path1, path2), "--in=%s"),
                CommandLineArgsFactory.from(ImmutableList.of(path3, path4), "--other-in=%s")));

    CommandLine cli =
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(filesystem)).build(args);

    assertEquals(
        ImmutableList.of(
            "foo",
            "bar",
            "--in=in1.txt",
            "--in=in2.txt",
            "--other-in=in3.txt",
            "--other-in=in4.txt"),
        cli.getCommandLineArgs());
    assertEquals(6, args.getEstimatedArgsCount());

    ImmutableSortedSet.Builder<Artifact> inputs = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<Artifact> outputs = ImmutableSortedSet.naturalOrder();
    args.visitInputsAndOutputs(inputs::add, outputs::add);

    assertEquals(ImmutableSortedSet.of(path1, path2, path3, path4), inputs.build());
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

    BuildArtifactFactoryForTests buildArtifactFactory =
        new BuildArtifactFactoryForTests(target, filesystem);
    Artifact path3 =
        buildArtifactFactory.createBuildArtifact(Paths.get("_foo_output_"), Location.BUILTIN);
    Artifact path4 =
        buildArtifactFactory.createBuildArtifact(Paths.get("_foo_output_"), Location.BUILTIN);
    Artifact path5 =
        buildArtifactFactory.createBuildArtifact(Paths.get("_bar_output_"), Location.BUILTIN);

    Artifact path1 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("some_bin")));
    Artifact path2 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("other_file")));
    RunInfo envArgs1 =
        createRunInfo(ImmutableSortedMap.of("FOO", "foo_val"), ImmutableList.of(path1));
    ListCommandLineArgs listArgs1 =
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2), CommandLineArgs.DEFAULT_FORMAT_STRING);
    RunInfo envArgs2 =
        createRunInfo(ImmutableSortedMap.of("FOO", "foo_val"), ImmutableList.of(path1));
    ListCommandLineArgs listArgs2 =
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2), CommandLineArgs.DEFAULT_FORMAT_STRING);
    RunInfo envArgs3 =
        createRunInfo(ImmutableSortedMap.of("BAR", "bar_val"), ImmutableList.of(path1));
    ListCommandLineArgs listArgs3 =
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2), CommandLineArgs.DEFAULT_FORMAT_STRING);
    RunInfo envArgs4 =
        createRunInfo(ImmutableSortedMap.of("FOO", "foo_val"), ImmutableList.of(path1));
    ListCommandLineArgs listArgs4 =
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2, path3), CommandLineArgs.DEFAULT_FORMAT_STRING);
    ListCommandLineArgs listArgs5 =
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2, path4), CommandLineArgs.DEFAULT_FORMAT_STRING);
    ListCommandLineArgs listArgs6 =
        new ListCommandLineArgs(
            ImmutableList.of(path1, 1, "foo", path2, path5), CommandLineArgs.DEFAULT_FORMAT_STRING);

    HashCode ruleKey1 =
        ruleKeyFactory
            .newBuilderForTesting(new FakeBuildRule(target))
            .setReflectively(
                "field", new AggregateCommandLineArgs(ImmutableList.of(envArgs1, listArgs1)))
            .build();
    HashCode ruleKey2 =
        ruleKeyFactory
            .newBuilderForTesting(new FakeBuildRule(target))
            .setReflectively(
                "field", new AggregateCommandLineArgs(ImmutableList.of(envArgs2, listArgs2)))
            .build();
    HashCode ruleKey3 =
        ruleKeyFactory
            .newBuilderForTesting(new FakeBuildRule(target))
            .setReflectively(
                "field", new AggregateCommandLineArgs(ImmutableList.of(envArgs3, listArgs3)))
            .build();

    hashCache.set(filesystem.resolve("some_bin"), HashCode.fromString("cccc"));
    HashCode ruleKey4 =
        ruleKeyFactory
            .newBuilderForTesting(new FakeBuildRule(target))
            .setReflectively(
                "field", new AggregateCommandLineArgs(ImmutableList.of(envArgs4, listArgs4)))
            .build();

    HashCode ruleKey5 =
        ruleKeyFactory
            .newBuilderForTesting(new FakeBuildRule(target))
            .setReflectively(
                "field", new AggregateCommandLineArgs(ImmutableList.of(envArgs4, listArgs5)))
            .build();

    HashCode ruleKey6 =
        ruleKeyFactory
            .newBuilderForTesting(new FakeBuildRule(target))
            .setReflectively(
                "field", new AggregateCommandLineArgs(ImmutableList.of(envArgs4, listArgs6)))
            .build();

    assertEquals(ruleKey1, ruleKey2);
    assertNotEquals(ruleKey1, ruleKey3);
    assertNotEquals(ruleKey1, ruleKey4);
    assertEquals(ruleKey4, ruleKey5);
    assertNotEquals(ruleKey4, ruleKey6);
  }
}
