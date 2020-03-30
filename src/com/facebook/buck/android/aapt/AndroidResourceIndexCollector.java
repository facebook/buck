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

package com.facebook.buck.android.aapt;

import com.facebook.buck.android.aapt.RDotTxtEntry.CustomDrawableType;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.xml.DocumentLocation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;
import javax.annotation.Nullable;

class AndroidResourceIndexCollector implements ResourceCollector {
  private final SortedSet<AndroidResourceIndexEntry> resources = new TreeSet<>();
  private final ProjectFilesystem projectFilesystem;

  public AndroidResourceIndexCollector(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = projectFilesystem;
  }

  @Override
  public void addIntResourceIfNotPresent(
      RDotTxtEntry.RType rType, String name, Path path, DocumentLocation documentLocation) {
    addResource(rType, name, documentLocation, path);
  }

  @Override
  public void addCustomDrawableResourceIfNotPresent(
      RDotTxtEntry.RType rType,
      String name,
      Path path,
      DocumentLocation documentLocation,
      CustomDrawableType drawableType) {
    addResource(rType, name, documentLocation, path);
  }

  @Override
  public void addIntArrayResourceIfNotPresent(
      RDotTxtEntry.RType rType,
      String name,
      int numValues,
      Path path,
      DocumentLocation documentLocation) {
    addResource(rType, name, documentLocation, path);
  }

  @Override
  public void addResource(
      RDotTxtEntry.RType rType,
      RDotTxtEntry.IdType idType,
      String name,
      String idValue,
      @Nullable String parent,
      Path path,
      DocumentLocation documentLocation) {
    addResource(rType, name, documentLocation, path);
  }

  public void addResource(
      RDotTxtEntry.RType rType, String name, DocumentLocation documentLocation, Path path) {
    // attempt to convert from symlink to real path
    AbsPath root = projectFilesystem.getRootPath();
    path = root.resolve(path).getPath();
    try {
      path = path.toFile().exists() ? root.relativize(path.toRealPath()).getPath() : path;
    } catch (IOException e) {
      throw new RuntimeException("failed in conversion from symlink to real path: " + path);
    }

    resources.add(
        ImmutableAndroidResourceIndexEntry.of(
            rType,
            name,
            documentLocation.getLineNumber(),
            documentLocation.getColumnNumber(),
            path));
  }

  public SortedSet<AndroidResourceIndexEntry> getResourceIndex() {
    return Collections.unmodifiableSortedSet(resources);
  }
}
