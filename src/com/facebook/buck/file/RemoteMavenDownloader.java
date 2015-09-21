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

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Path;

public class RemoteMavenDownloader implements Downloader {

  private final HttpDownloader downloader;
  private final Optional<String> mavenRepo;

  public RemoteMavenDownloader(Optional<Proxy> proxy, String mavenRepo) {
    this.mavenRepo = Optional.of(mavenRepo);
    this.downloader = new HttpDownloader(proxy);
  }

  @Override
  public boolean fetch(BuckEventBus eventBus, URI uri, Path output) throws IOException {
    if (!"mvn".equals(uri.getScheme())) {
      return false;
    }

    URI httpUri = MavenUrlDecoder.toHttpUrl(mavenRepo, uri);

    return downloader.fetch(eventBus, httpUri, output);
  }
}
