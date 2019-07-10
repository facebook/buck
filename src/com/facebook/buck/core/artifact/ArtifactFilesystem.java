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

import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Objects;

/** A Filesystem for operating on {@link com.facebook.buck.core.artifact.Artifact}s */
public class ArtifactFilesystem {

  private final ProjectFilesystem filesystem;

  /** @param filesystem that the {@link Artifact}s are relative to. */
  public ArtifactFilesystem(ProjectFilesystem filesystem) {
    this.filesystem = filesystem;
  }

  /**
   * @param artifact the artifact to read. It should be bound.
   * @return an {@link InputStream} of the given artifact.
   * @throws IOException
   */
  public InputStream getInputStream(Artifact artifact) throws IOException {
    return filesystem.newFileInputStream(resolveToPath(artifact));
  }

  /**
   * @param artifact the artifact to write. It should be bound.
   * @return an {@link java.io.OutputStream} of the given artifact.
   * @throws IOException
   */
  public OutputStream getOutputStream(Artifact artifact, FileAttribute<?>... attrs)
      throws IOException {
    Path path = resolveToPath(artifact);
    filesystem.createParentDirs(path);
    return filesystem.newFileOutputStream(path, attrs);
  }

  private Path resolveToPath(Artifact artifact) {
    BoundArtifact boundArtifact = artifact.asBound();

    BuildArtifact buildArtifact = boundArtifact.asBuildArtifact();
    if (buildArtifact != null) {
      return buildArtifact.getSourcePath().getResolvedPath();
    }

    return Objects.requireNonNull(boundArtifact.asSource()).getSourcePath().getRelativePath();
  }

  /**
   * Write a string to a given {@link Artifact}, creating parent directories as necessary
   *
   * @param contents the desired contents
   * @param artifact the artifact to write. It must be bound.
   * @throws IOException The file could not be written
   */
  public void writeContentsToPath(String contents, Artifact artifact) throws IOException {
    Path path = resolveToPath(artifact);
    filesystem.createParentDirs(path);
    filesystem.writeContentsToPath(contents, path);
  }

  /**
   * Make an {@link Artifact} executable
   *
   * @param artifact the artifact to write. It must be bound.
   * @throws IOException Making the file executable failed
   */
  public void makeExecutable(Artifact artifact) throws IOException {
    Path path = resolveToPath(artifact);
    MostFiles.makeExecutable(filesystem.resolve(path));
  }

  /**
   * Expand an artifact into a command line argument.
   *
   * <p>NOTE: This should not be used just to get a string version of a path. This API may become
   * more restrictive in the future if necessary.
   *
   * @param artifact a bound artifact whose path is requested
   * @return The path to an artifact as a string
   */
  public String stringifyForCommandLine(Artifact artifact) {
    /*
     * We return an absolute path here because sometimes it's required. An example is when the
     * artifact is a binary at the root of the tree. If you try to just run 'foo.sh' in exec, it
     * will fail. This is cleaner cluttering the {@link
     * CommandLineArg#getString(ArtifactFilesystem)} api that calls this method with "get relative"
     * or "get absolute", but if it becomes a problem we can refactor that API
     */
    return filesystem.resolve(resolveToPath(artifact)).toAbsolutePath().toString();
  }
}
