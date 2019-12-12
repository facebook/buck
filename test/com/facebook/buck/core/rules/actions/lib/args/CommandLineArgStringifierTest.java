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
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import java.nio.file.Paths;
import org.junit.Test;

public class CommandLineArgStringifierTest {
  @Test
  public void convertsStringToString() {
    assertEquals(
        "foo",
        CommandLineArgStringifier.asString(
            new ArtifactFilesystem(new FakeProjectFilesystem()), false, "foo"));
  }

  @Test
  public void convertsIntegerToString() {
    assertEquals(
        "1",
        CommandLineArgStringifier.asString(
            new ArtifactFilesystem(new FakeProjectFilesystem()), false, 1));
  }

  @Test
  public void convertsLabelToString() throws LabelSyntaxException {
    assertEquals(
        "//foo:bar",
        CommandLineArgStringifier.asString(
            new ArtifactFilesystem(new FakeProjectFilesystem()),
            false,
            Label.parseAbsolute("//foo:bar", ImmutableMap.of())));
  }

  @Test
  public void convertsArtifactToString() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Artifact artifact =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("foo", "bar.cpp")));
    Artifact shortArtifact =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("foo")));

    assertEquals(
        Paths.get("foo", "bar.cpp").toString(),
        CommandLineArgStringifier.asString(new ArtifactFilesystem(filesystem), false, artifact));
    assertEquals(
        Paths.get("foo").toString(),
        CommandLineArgStringifier.asString(
            new ArtifactFilesystem(filesystem), false, shortArtifact));
    assertEquals(
        filesystem.resolve(Paths.get("foo", "bar.cpp")).toAbsolutePath().toString(),
        CommandLineArgStringifier.asString(new ArtifactFilesystem(filesystem), true, artifact));
    assertEquals(
        filesystem.resolve(Paths.get("foo")).toAbsolutePath().toString(),
        CommandLineArgStringifier.asString(
            new ArtifactFilesystem(filesystem), true, shortArtifact));
  }
}
