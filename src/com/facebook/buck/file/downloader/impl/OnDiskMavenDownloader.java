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

package com.facebook.buck.file.downloader.impl;

import com.facebook.buck.file.downloader.Downloader;
import java.io.FileNotFoundException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/** A {@link Downloader} that pulls content in maven format from the local file system. */
public class OnDiskMavenDownloader extends AbstractOnDiskDownloader {
  private static final Optional<String> SPOOF_MAVEN_REPO = Optional.of("http://example.com");

  public OnDiskMavenDownloader(Path root) throws FileNotFoundException {
    super(root);
  }

  @Override
  public Path getTargetPath(URI uri) {
    // Get the URL that the maven item is located at.
    URI decodedUri = MavenUrlDecoder.toHttpUrl(SPOOF_MAVEN_REPO, uri);

    // The path is absolute, which we don't want.
    // Make it relative by just ignoring the leading slash.
    return getRoot().resolve(decodedUri.getPath().substring(1));
  }
}
