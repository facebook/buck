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

package com.facebook.buck.apple.clang;

import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.types.Pair;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import java.io.IOException;
import java.nio.file.Path;

/**
 * VFSOverlays are used for similar purposes to headermaps, but can be used to overlay more than
 * headers on the filesystem (such as modulemaps)
 *
 * <p>This class provides support for reading and generating clang vfs overlays. No spec is
 * available but we conform to the https://clang.llvm.org/doxygen/VirtualFileSystem_8cpp_source.html
 * writer class defined in the Clang documentation.
 */
@JsonSerialize(as = VFSOverlay.class)
public class VFSOverlay {

  @SuppressWarnings("PMD.UnusedPrivateField")
  @JsonProperty
  private final int version = 0;

  @SuppressWarnings("PMD.UnusedPrivateField")
  @JsonProperty("case-sensitive")
  private final boolean case_sensitive = false;

  @JsonProperty("roots")
  private ImmutableList<VirtualDirectory> computeRoots() {
    Multimap<Path, Pair<Path, Path>> byParent = MultimapBuilder.hashKeys().hashSetValues().build();
    overlays.forEach(
        (virtual, real) -> {
          byParent.put(virtual.getParent(), new Pair<>(virtual.getFileName(), real));
        });
    return byParent
        .asMap()
        .entrySet()
        .stream()
        .map(
            e ->
                new VirtualDirectory(
                    e.getKey(),
                    e.getValue()
                        .stream()
                        .map(x -> new VirtualFile(x.getFirst(), x.getSecond()))
                        .collect(ImmutableList.toImmutableList())))
        .collect(ImmutableList.toImmutableList());
  }

  private final ImmutableSortedMap<Path, Path> overlays;

  public VFSOverlay(ImmutableSortedMap<Path, Path> overlays) {
    this.overlays = overlays;
  }

  public String render() throws IOException {
    return ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValueAsString(this);
  }

  @JsonSerialize(as = VirtualDirectory.class)
  private class VirtualDirectory {

    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonProperty
    private final String type = "directory";

    @JsonProperty private final Path name;

    @JsonProperty("contents")
    private final ImmutableList<VirtualFile> fileList;

    public VirtualDirectory(Path root, ImmutableList<VirtualFile> fileList) {
      this.name = root;
      this.fileList = fileList;
    }
  }

  @JsonSerialize(as = VirtualFile.class)
  private class VirtualFile {
    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonProperty
    private final String type = "file";

    @JsonProperty private final Path name;

    @JsonProperty("external-contents")
    private final Path realPath;

    public VirtualFile(Path name, Path realPath) {
      Preconditions.checkState(
          realPath.isAbsolute(),
          "Attempting to make vfsoverlay with non-absolute path '%s' for "
              + "external contents field. Only absolute paths are currently supported.",
          realPath);
      this.name = name;
      this.realPath = realPath;
    }
  }
}
