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

package com.facebook.buck.file.downloader.impl;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.file.downloader.impl.DownloadEvent.Started;
import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** A {@link Downloader} that pulls content from the local file system. */
public abstract class AbstractOnDiskDownloader implements Downloader {
  private final Path root;

  public AbstractOnDiskDownloader(Path root) throws FileNotFoundException {
    if (!Files.exists(root)) {
      throw new FileNotFoundException(
          String.format("Maven root %s doesn't exist", root.toString()));
    }

    this.root = root;
  }

  @Override
  public boolean fetch(BuckEventBus eventBus, URI uri, Path output) throws IOException {
    if (!"mvn".equals(uri.getScheme())) {
      return false;
    }

    Path target = getTargetPath(uri);

    if (!Files.exists(target)) {
      throw new IOException(String.format("Unable to download %s (derived from %s)", target, uri));
    }

    Started started = DownloadEvent.started(target.toUri());
    eventBus.post(started);

    try (InputStream is = new BufferedInputStream(Files.newInputStream(target))) {
      Files.copy(is, output);
    } finally {
      eventBus.post(DownloadEvent.finished(started));
    }
    return true;
  }

  public Path getRoot() {
    return root;
  }

  public abstract Path getTargetPath(URI uri);
}
