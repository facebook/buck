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
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.events.Location;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.stream.Stream;
import org.junit.Test;

public class AggregateCommandLineArgsTest {

  static class EnvCommandLineArgs implements CommandLineArgs {
    private final ImmutableSortedMap<String, String> env;
    private final ImmutableList<Object> args;

    EnvCommandLineArgs(ImmutableSortedMap<String, String> env, ImmutableList<Object> args) {
      this.env = env;
      this.args = args;
    }

    @Override
    public ImmutableSortedMap<String, String> getEnvironmentVariables() {
      return env;
    }

    @Override
    public Stream<Object> getArgs() {
      return args.stream();
    }

    @Override
    public int getEstimatedArgsCount() {
      return args.size();
    }
  }

  @Test
  public void returnsProperStreamAndArgCount() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Artifact path1 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("some_bin")));
    Artifact path2 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("other_file")));

    CommandLineArgs args =
        new AggregateCommandLineArgs(
            ImmutableList.of(
                new EnvCommandLineArgs(
                    ImmutableSortedMap.of("FOO", "foo_val"), ImmutableList.of(path1)),
                new EnvCommandLineArgs(
                    ImmutableSortedMap.of("BAZ", "baz_val"), ImmutableList.of(path2, 1)),
                CommandLineArgsFactory.from(ImmutableList.of("foo", "bar"))));

    CommandLine cli =
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(filesystem)).build(args);

    assertEquals(
        ImmutableList.of(
            filesystem.resolve("some_bin").toAbsolutePath().toString(),
            "other_file",
            "1",
            "foo",
            "bar"),
        cli.getCommandLineArgs());
    assertEquals(5, args.getEstimatedArgsCount());
    assertEquals(
        ImmutableSortedMap.of("FOO", "foo_val", "BAZ", "baz_val"), cli.getEnvironmentVariables());
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
    EnvCommandLineArgs envArgs1 =
        new EnvCommandLineArgs(ImmutableSortedMap.of("FOO", "foo_val"), ImmutableList.of(path1));
    ListCommandLineArgs listArgs1 =
        new ListCommandLineArgs(ImmutableList.of(path1, 1, "foo", path2));
    EnvCommandLineArgs envArgs2 =
        new EnvCommandLineArgs(ImmutableSortedMap.of("FOO", "foo_val"), ImmutableList.of(path1));
    ListCommandLineArgs listArgs2 =
        new ListCommandLineArgs(ImmutableList.of(path1, 1, "foo", path2));
    EnvCommandLineArgs envArgs3 =
        new EnvCommandLineArgs(ImmutableSortedMap.of("BAR", "bar_val"), ImmutableList.of(path1));
    ListCommandLineArgs listArgs3 =
        new ListCommandLineArgs(ImmutableList.of(path1, 1, "foo", path2));
    EnvCommandLineArgs envArgs4 =
        new EnvCommandLineArgs(ImmutableSortedMap.of("FOO", "foo_val"), ImmutableList.of(path1));
    ListCommandLineArgs listArgs4 =
        new ListCommandLineArgs(ImmutableList.of(path1, 1, "foo", path2, path3));
    ListCommandLineArgs listArgs5 =
        new ListCommandLineArgs(ImmutableList.of(path1, 1, "foo", path2, path4));
    ListCommandLineArgs listArgs6 =
        new ListCommandLineArgs(ImmutableList.of(path1, 1, "foo", path2, path5));

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
