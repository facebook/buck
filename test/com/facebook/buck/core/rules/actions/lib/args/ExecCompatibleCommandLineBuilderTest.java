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

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.artifact.ImmutableSourceArtifactImpl;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ExecCompatibleCommandLineBuilderTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  private static class TestCommandLineArgs implements CommandLineArgs {
    private final ImmutableSortedMap<String, String> env;
    private final ImmutableList<Object> args;

    private TestCommandLineArgs(
        ImmutableSortedMap<String, String> env, ImmutableList<Object> args) {
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
  public void stringifiesProperly() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Artifact path1 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("some_bin")));
    Artifact path2 =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("other_file")));
    Artifact path3 =
        ImmutableSourceArtifactImpl.of(
            PathSourcePath.of(filesystem, Paths.get("subdir", "some_bin")));

    CommandLineArgs args1 =
        new TestCommandLineArgs(
            ImmutableSortedMap.of(), ImmutableList.of(path1, path2, 1, "foo", "bar"));

    CommandLineArgs args2 =
        new TestCommandLineArgs(
            ImmutableSortedMap.of("FOO", "bar"), ImmutableList.of(path3, path2, 1, "foo", "bar"));

    assertEquals(
        ImmutableList.of(
            filesystem.resolve("some_bin").toAbsolutePath().toString(),
            "other_file",
            "1",
            "foo",
            "bar"),
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(filesystem))
            .build(args1)
            .getCommandLineArgs());
    CommandLine cli =
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(filesystem)).build(args2);
    assertEquals(
        ImmutableList.of(
            filesystem.resolve(Paths.get("subdir", "some_bin")).toAbsolutePath().toString(),
            "other_file",
            "1",
            "foo",
            "bar"),
        cli.getCommandLineArgs());
    assertEquals(ImmutableSortedMap.of("FOO", "bar"), cli.getEnvironmentVariables());
  }

  @Test
  public void throwsOnNonStingifiableObject() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    CommandLineArgs args =
        new TestCommandLineArgs(ImmutableSortedMap.of(), ImmutableList.of(ImmutableList.of("foo")));

    thrown.expect(CommandLineArgException.class);

    new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(filesystem))
        .build(args)
        .getCommandLineArgs();
  }

  @Test
  public void throwsOnDuplicateEnvironmentVariables() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    CommandLineArgs args =
        CommandLineArgsFactory.from(
            ImmutableList.of(
                new TestCommandLineArgs(
                    ImmutableSortedMap.of("foo", "foo_val1", "bar", "bar_val"),
                    ImmutableList.of("arg")),
                new TestCommandLineArgs(
                    ImmutableSortedMap.of("foo", "foo_val2", "baz", "baz_val"),
                    ImmutableList.of("arg"))));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Error getting commandline arguments: Multiple entries with same key: foo=");
    new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(filesystem))
        .build(args)
        .getEnvironmentVariables();
  }
}
