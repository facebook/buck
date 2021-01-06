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

package com.facebook.buck.core.files;

import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;

/** Has information about files and folders at some directory */
@BuckStyleValue
public abstract class FileTree implements ComputeResult {

  /** Relative path to this instance from some root, usually cell root */
  public abstract Path getPath();

  /** List of files, folders and symlinks in the desired directory */
  public abstract DirectoryList getDirectoryList();

  /** File trees of all subfolders recursively */
  public abstract ImmutableMap<Path, FileTree> getChildren();
}
