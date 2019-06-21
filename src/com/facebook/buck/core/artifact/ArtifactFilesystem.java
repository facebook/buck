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

    BuildArtifactApi buildArtifact = boundArtifact.asBuildArtifact();
    if (buildArtifact != null) {
      return buildArtifact.getSourcePath().getResolvedPath();
    }

    return Objects.requireNonNull(boundArtifact.asSource()).getSourcePath().getRelativePath();
  }
}
