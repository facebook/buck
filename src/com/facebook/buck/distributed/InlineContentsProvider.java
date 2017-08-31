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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class InlineContentsProvider implements FileContentsProvider {

  final ListeningExecutorService executor;

  public InlineContentsProvider(ListeningExecutorService executor) {
    this.executor = executor;
  }

  private boolean materializeFileContents(BuildJobStateFileHashEntry entry, Path targetAbsPath)
      throws IOException {
    if (entry.isSetContents()) {
      try (OutputStream outputStream = newOutputStream(targetAbsPath)) {
        outputStream.write(entry.getContents());
      }

      return true;
    }

    return false;
  }

  @Override
  public ListenableFuture<Boolean> materializeFileContentsAsync(
      BuildJobStateFileHashEntry entry, Path targetAbsPath) {
    return executor.submit(() -> materializeFileContents(entry, targetAbsPath));
  }

  public static OutputStream newOutputStream(Path absPath) throws IOException {
    Files.createDirectories(absPath.getParent());
    return new BufferedOutputStream(new FileOutputStream(absPath.toFile()));
  }

  @Override
  public void close() {}
}
