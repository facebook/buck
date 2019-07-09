/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.artifact;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Charsets;
import com.google.common.collect.Iterables;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;

public class ArtifactFilesystemTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

  @Test
  public void inputStreamOfArtifact() throws IOException {
    ArtifactFilesystem artifactFilesystem = new ArtifactFilesystem(filesystem);

    filesystem.writeContentsToPath("foo", Paths.get("bar"));
    InputStream inputStream =
        artifactFilesystem.getInputStream(
            ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("bar"))));

    assertEquals("foo", new BufferedReader(new InputStreamReader(inputStream)).readLine());
  }

  @Test
  public void outputStreamOfArtifact() throws IOException {
    ArtifactFilesystem artifactFilesystem = new ArtifactFilesystem(filesystem);

    OutputStream outputStream =
        artifactFilesystem.getOutputStream(
            ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("bar"))));

    outputStream.write("foo".getBytes(Charsets.UTF_8));
    outputStream.close();

    assertEquals("foo", Iterables.getOnlyElement(filesystem.readLines(Paths.get("bar"))));
  }

  @Test
  public void makeExecutable() throws IOException {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    ArtifactFilesystem artifactFilesystem = new ArtifactFilesystem(filesystem);
    ImmutableSourceArtifactImpl artifact =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("bar")));

    artifactFilesystem.writeContentsToPath("foobar", artifact);
    artifactFilesystem.makeExecutable(artifact);

    assertEquals("foobar", Iterables.getOnlyElement(filesystem.readLines(Paths.get("bar"))));
    assertTrue(filesystem.isExecutable(artifact.getSourcePath().getRelativePath()));
  }

  @Test
  public void writeContents() throws IOException {
    ArtifactFilesystem artifactFilesystem = new ArtifactFilesystem(filesystem);
    ImmutableSourceArtifactImpl artifact =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("bar")));

    artifactFilesystem.writeContentsToPath("foobar", artifact);

    assertEquals("foobar", Iterables.getOnlyElement(filesystem.readLines(Paths.get("bar"))));
  }

  @Test
  public void expandCommandLine() {
    ArtifactFilesystem artifactFilesystem = new ArtifactFilesystem(filesystem);
    ImmutableSourceArtifactImpl sourceArtifact =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("bar", "baz")));

    assertEquals(
        filesystem.resolve("bar").resolve("baz").toAbsolutePath().toString(),
        artifactFilesystem.stringifyForCommandLine(sourceArtifact));
  }
}
