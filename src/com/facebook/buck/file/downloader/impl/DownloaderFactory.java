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

package com.facebook.buck.file.downloader.impl;

import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.file.downloader.Downloader;
import java.util.Optional;

public class DownloaderFactory implements ToolchainFactory<Downloader> {

  @Override
  public Optional<Downloader> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
    // Prepare the downloader if we're allowing mid-build downloads
    Downloader downloader;
    DownloadConfig downloadConfig = new DownloadConfig(context.getBuckConfig());
    if (downloadConfig.isDownloadAtRuntimeOk()) {
      downloader = StackedDownloader.createFromConfig(context.getBuckConfig(), toolchainProvider);
    } else {
      // Or just set one that blows up
      downloader = new ExplodingDownloader();
    }
    return Optional.of(downloader);
  }
}
