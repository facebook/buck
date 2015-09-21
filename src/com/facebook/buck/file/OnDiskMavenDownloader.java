/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.file;

import com.facebook.buck.event.BuckEventBus;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A {@link Downloader} that pulls content from the local file system.
 */
public class OnDiskMavenDownloader implements Downloader {

  private static final Optional<String> SPOOF_MAVEN_REPO = Optional.of("http://example.com");
  private final Path root;

  public OnDiskMavenDownloader(Path root) {
    Preconditions.checkArgument(Files.exists(root), "Root does not exist");
    this.root = root;
  }

  @Override
  public boolean fetch(BuckEventBus eventBus, URI uri, Path output) throws IOException {
    if (!"mvn".equals(uri.getScheme())) {
      return false;
    }

    // Get the URL that the maven item is located at.
    uri = MavenUrlDecoder.toHttpUrl(SPOOF_MAVEN_REPO, uri);

    // Now grab the path from the URI and resolve it against the root
    uri = root.resolve(uri.getPath()).toUri();

    // The path is absolute, which we don't want. Make it relative by just ignoring the leading
    // slash.
    Path target = root.resolve(uri.getPath().substring(1));

    if (!Files.exists(target)) {
      throw new IOException(String.format("Unable to download %s (derived from %s)", target, uri));
    }

    DownloadEvent.Started started = DownloadEvent.started(uri);
    eventBus.post(started);

    try (InputStream is = new BufferedInputStream(Files.newInputStream(target))) {
      Files.copy(is, output);
    } finally {
      eventBus.post(DownloadEvent.finished(started));
    }
    return true;
  }
}
