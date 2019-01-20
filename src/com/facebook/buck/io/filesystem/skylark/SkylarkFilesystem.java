/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.io.filesystem.skylark;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import com.google.devtools.build.lib.vfs.Path;

/**
 * {@link FileSystem} implementation that uses underlying {@link ProjectFilesystem} to resolve
 * {@link Path}s to {@link java.nio.file.Path} and perform most of the operations by delegation.
 *
 * <p>Skylark uses its own {@link FileSystem} API, which operates on {@link Path} abstraction not
 * used anywhere else in Buck, but it should be based on {@link ProjectFilesystem} for
 * interoperability.
 *
 * <p>Ideally {@link com.google.devtools.build.lib.vfs.JavaIoFileSystem} should be extended, but
 * unfortunately it resolves all paths to {@link java.io.File} instead of {@link java.nio.file.Path}
 * which means that it wouldn't play nicely with in-memory {@link ProjectFilesystem}.
 *
 * <p>Since every method has to resolve {@link Path} into {@link java.nio.file.Path}, it might
 * become expensive or cause excessive allocations, so caching might be beneficial.
 */
public class SkylarkFilesystem extends JavaIoFileSystem {

  private final ProjectFilesystem filesystem;

  private SkylarkFilesystem(ProjectFilesystem filesystem) {
    super(DigestHashFunction.SHA1);
    this.filesystem = filesystem;
  }

  @Override
  protected java.nio.file.Path getNioPath(Path path) {
    return filesystem.resolve(path.toString());
  }

  /** @return The {@link SkylarkFilesystem} which methods */
  public static SkylarkFilesystem using(ProjectFilesystem projectFilesystem) {
    return new SkylarkFilesystem(projectFilesystem);
  }
}
