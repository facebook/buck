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

package com.facebook.buck.core.artifact;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.EvalException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;

public class ArtifactFilesystemTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final BuildArtifactFactoryForTests artifactFactory =
      new BuildArtifactFactoryForTests(BuildTargetFactory.newInstance("//test:foo"), filesystem);

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
    BuildArtifact artifact =
        artifactFactory.createBuildArtifact(Paths.get("bar"), Location.BUILTIN);

    OutputStream outputStream = artifactFilesystem.getOutputStream(artifact);

    outputStream.write("foo".getBytes(Charsets.UTF_8));
    outputStream.close();

    assertEquals(
        "foo",
        Iterables.getOnlyElement(filesystem.readLines(artifact.getSourcePath().getResolvedPath())));
  }

  @Test
  public void makeExecutable() throws IOException {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    ArtifactFilesystem artifactFilesystem = new ArtifactFilesystem(filesystem);
    BuildArtifact artifact =
        artifactFactory.createBuildArtifact(Paths.get("bar"), Location.BUILTIN);

    artifactFilesystem.writeContentsToPath("foobar", artifact);
    artifactFilesystem.makeExecutable(artifact);

    assertEquals(
        "foobar",
        Iterables.getOnlyElement(filesystem.readLines(artifact.getSourcePath().getResolvedPath())));
    assertTrue(filesystem.isExecutable(artifact.getSourcePath().getResolvedPath()));
  }

  @Test
  public void writeContents() throws IOException {
    ArtifactFilesystem artifactFilesystem = new ArtifactFilesystem(filesystem);
    BuildArtifact artifact =
        artifactFactory.createBuildArtifact(Paths.get("bar"), Location.BUILTIN);

    artifactFilesystem.writeContentsToPath("foobar", artifact);

    assertEquals(
        "foobar",
        Iterables.getOnlyElement(filesystem.readLines(artifact.getSourcePath().getResolvedPath())));
  }

  @Test
  public void copiesArtifacts() throws IOException {
    ArtifactFilesystem artifactFilesystem = new ArtifactFilesystem(filesystem);
    ImmutableSourceArtifactImpl artifact =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("bar")));
    filesystem.writeContentsToPath("some contents", Paths.get("bar"));

    BuildArtifact dest = artifactFactory.createBuildArtifact(Paths.get("out"), Location.BUILTIN);

    artifactFilesystem.copy(artifact, dest, CopySourceMode.FILE);

    assertEquals(
        "some contents",
        filesystem.readFileIfItExists(dest.getSourcePath().getResolvedPath()).get());
  }

  @Test
  public void expandCommandLine() {
    ArtifactFilesystem artifactFilesystem = new ArtifactFilesystem(filesystem);
    ImmutableSourceArtifactImpl sourceArtifact =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("bar", "baz")));
    ImmutableSourceArtifactImpl shortArtifact =
        ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, Paths.get("foo")));

    assertEquals(Paths.get("bar", "baz").toString(), artifactFilesystem.stringify(sourceArtifact));
    assertEquals(Paths.get("foo").toString(), artifactFilesystem.stringify(shortArtifact));
    assertEquals(
        filesystem.resolve(Paths.get("bar", "baz")).toAbsolutePath().toString(),
        artifactFilesystem.stringifyAbsolute(sourceArtifact));
    assertEquals(
        filesystem.resolve("foo").toAbsolutePath().toString(),
        artifactFilesystem.stringifyAbsolute(shortArtifact));
  }

  @Test
  public void createsPackagePathsForOutputs() throws IOException, EvalException {
    ArtifactFilesystem artifactFilesystem = new ArtifactFilesystem(filesystem);
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    BuildArtifactFactory factory = new BuildArtifactFactory(buildTarget, filesystem);
    ActionAnalysisDataKey key =
        ActionAnalysisDataKey.of(buildTarget, new ActionAnalysisData.ID("a"));

    DeclaredArtifact declaredArtifact =
        factory.createDeclaredArtifact(Paths.get("out.txt"), Location.BUILTIN);
    OutputArtifact outputArtifact = declaredArtifact.asOutputArtifact();
    declaredArtifact.materialize(key);

    Path expectedPath = BuildPaths.getGenDir(filesystem, buildTarget);

    assertFalse(filesystem.isDirectory(expectedPath));

    artifactFilesystem.createPackagePaths(ImmutableSortedSet.of(outputArtifact));

    assertTrue(filesystem.isDirectory(expectedPath));
  }

  @Test
  public void deletesBuildArtifactsForOutputs() throws IOException, EvalException {
    ArtifactFilesystem artifactFilesystem = new ArtifactFilesystem(filesystem);
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");

    BuildArtifactFactory factory = new BuildArtifactFactory(buildTarget, filesystem);
    ActionAnalysisDataKey key =
        ActionAnalysisDataKey.of(buildTarget, new ActionAnalysisData.ID("a"));

    DeclaredArtifact declaredArtifact =
        factory.createDeclaredArtifact(Paths.get("out.txt"), Location.BUILTIN);
    OutputArtifact outputArtifact = declaredArtifact.asOutputArtifact();
    BuildArtifact artifact = declaredArtifact.materialize(key);

    artifactFilesystem.writeContentsToPath("contents", artifact);

    Path expectedBuildPath = BuildPaths.getGenDir(filesystem, buildTarget).resolve("out.txt");

    assertTrue(filesystem.isFile(expectedBuildPath));

    artifactFilesystem.removeBuildArtifacts(ImmutableSet.of(outputArtifact));

    assertFalse(filesystem.isFile(expectedBuildPath));
  }
}
