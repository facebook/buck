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
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.nio.file.Paths;
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
}
