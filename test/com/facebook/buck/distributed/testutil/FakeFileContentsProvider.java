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

package com.facebook.buck.distributed.testutil;

import com.facebook.buck.distributed.FileContentsProvider;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class FakeFileContentsProvider implements FileContentsProvider {

  public static class Builder {
    private ProjectFilesystem filesystem = new FakeProjectFilesystem();
    private Map<String, String> fileContents = new HashMap<>();

    public Builder setFilesystemToWrite(ProjectFilesystem filesystem) {
      this.filesystem = filesystem;
      return this;
    }

    public Builder putAllFileContents(Map<String, String> fileContents) {
      this.fileContents.putAll(fileContents);
      return this;
    }

    public Builder putFileContents(String path, String contents) {
      this.fileContents.put(path, contents);
      return this;
    }

    public FakeFileContentsProvider build() {
      return new FakeFileContentsProvider(filesystem, fileContents);
    }
  }

  private final ProjectFilesystem filesystem;
  private final Map<String, String> fileContents;

  private FakeFileContentsProvider(ProjectFilesystem filesystem, Map<String, String> fileContents) {
    this.filesystem = filesystem;
    this.fileContents = fileContents;
  }

  @Override
  public void close() {}

  private boolean materializeFileContents(BuildJobStateFileHashEntry entry, Path targetAbsPath)
      throws IOException {
    filesystem.writeContentsToPath(
        Preconditions.checkNotNull(
            fileContents.get(entry.getPath().getPath()),
            "File at path [%s] does not exist. Only recorded paths are: [%s].",
            entry.getPath().getPath(),
            String.join(", ", fileContents.keySet())),
        filesystem.getPathRelativeToProjectRoot(targetAbsPath).get());
    return true;
  }

  @Override
  public ListenableFuture<Boolean> materializeFileContentsAsync(
      BuildJobStateFileHashEntry entry, Path targetAbsPath) {
    try {
      return Futures.immediateFuture(materializeFileContents(entry, targetAbsPath));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
