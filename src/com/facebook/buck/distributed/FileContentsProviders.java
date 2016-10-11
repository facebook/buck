/*
 * Copyright 2016-present Facebook, Inc.
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
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public abstract class FileContentsProviders {
  private FileContentsProviders() {
    // Do not instantiate.
  }

  public static FileContentsProvider createDefaultProvider(DistBuildService service) {
    return new FileContentsProviders.MultiSourceContentsProvider(
        new FileContentsProviders.InlineContentsProvider(),
        new FileContentsProviders.ServerContentsProvider(service)
    );
  }

  public static class InlineContentsProvider implements FileContentsProvider {
    @Override
    public Optional<InputStream> getFileContents(BuildJobStateFileHashEntry entry) {
      if (entry.isSetContents()) {
        byte[] contents = entry.getContents();
        return Optional.of(new ByteArrayInputStream(contents));
      }

      return Optional.absent();
    }
  }

  public static class MultiSourceContentsProvider implements FileContentsProvider {
    private final ImmutableList<FileContentsProvider> providers;

    public MultiSourceContentsProvider(FileContentsProvider... providers) {
      this.providers = ImmutableList.copyOf(providers);
    }

    @Override
    public Optional<InputStream> getFileContents(BuildJobStateFileHashEntry entry)
        throws IOException {
      for (FileContentsProvider provider : providers) {
        Optional<InputStream> stream = provider.getFileContents(entry);
        if (stream.isPresent()) {
          return stream;
        }
      }

      return Optional.absent();
    }
  }

  public static class ServerContentsProvider implements FileContentsProvider {
    private final DistBuildService service;

    public ServerContentsProvider(DistBuildService service) {
      this.service = service;
    }

    @Override
    public Optional<InputStream> getFileContents(BuildJobStateFileHashEntry entry)
        throws IOException {
      Preconditions.checkState(
          entry.isSetHashCode(),
          String.format("File hash missing for file [%s]", entry.getPath()));
      return Optional.of(service.fetchSourceFile(entry.getHashCode()));
    }
  }
}
