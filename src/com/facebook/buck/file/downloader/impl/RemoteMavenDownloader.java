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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.file.downloader.AuthAwareDownloader;
import com.facebook.buck.file.downloader.Downloader;
import java.io.IOException;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

public class RemoteMavenDownloader implements Downloader {

  private final AuthAwareDownloader downloader;
  private final Optional<String> mavenRepo;
  private final Optional<PasswordAuthentication> passwordAuthentication;

  public RemoteMavenDownloader(
      AuthAwareDownloader downloader,
      String mavenRepo,
      Optional<PasswordAuthentication> passwordAuthentication) {
    this.mavenRepo = Optional.of(mavenRepo);
    this.downloader = downloader;
    this.passwordAuthentication = passwordAuthentication;
  }

  @Override
  public boolean fetch(BuckEventBus eventBus, URI uri, Path output) throws IOException {
    if (!"mvn".equals(uri.getScheme())) {
      return false;
    }

    URI httpUri = MavenUrlDecoder.toHttpUrl(mavenRepo, uri);

    return downloader.fetch(eventBus, httpUri, passwordAuthentication, output);
  }
}
