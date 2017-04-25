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
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class MultiSourceContentsProvider implements FileContentsProvider {
  private final FileContentsProvider serverProvider;
  private final Optional<LocalFsContentsProvider> localFsProvider;
  private final InlineContentsProvider inlineProvider;

  public MultiSourceContentsProvider(DistBuildService service, Optional<Path> localCacheAbsPath)
      throws InterruptedException, IOException {
    this(new ServerContentsProvider(service), localCacheAbsPath);
  }

  public MultiSourceContentsProvider(
      FileContentsProvider serverContentProvider, Optional<Path> localCacheAbsPath)
      throws InterruptedException, IOException {
    this.inlineProvider = new InlineContentsProvider();
    if (localCacheAbsPath.isPresent()) {
      this.localFsProvider = Optional.of(new LocalFsContentsProvider(localCacheAbsPath.get()));
    } else {
      this.localFsProvider = Optional.empty();
    }

    this.serverProvider = serverContentProvider;
  }

  @Override
  public boolean materializeFileContents(BuildJobStateFileHashEntry entry, Path targetAbsPath)
      throws IOException {

    if (inlineProvider.materializeFileContents(entry, targetAbsPath)) {
      return true;
    }

    if (localFsProvider.isPresent()
        && localFsProvider.get().materializeFileContents(entry, targetAbsPath)) {
      return true;
    }

    if (serverProvider.materializeFileContents(entry, targetAbsPath)) {
      if (localFsProvider.isPresent()) {
        localFsProvider.get().writeFileAndGetInputStream(entry, targetAbsPath);
      }

      return true;
    }

    return false;
  }
}
