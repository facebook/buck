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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.DownloadConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A {@link Downloader} which is composed of many other downloaders. When asked to download a
 * resource, these are each called in order until one succeeds or the final one fails.
 */
public class StackedDownloader implements Downloader {

  private static final Logger LOG = Logger.get(StackedDownloader.class);
  private final ImmutableList<Downloader> delegates;

  @VisibleForTesting
  StackedDownloader(ImmutableList<Downloader> delegates) {
    Preconditions.checkArgument(delegates.size() > 0);
    this.delegates = delegates;
  }

  public static Downloader createFromConfig(BuckConfig config, Optional<Path> androidSdkRoot) {
    ImmutableList.Builder<Downloader> downloaders = ImmutableList.builder();

    DownloadConfig downloadConfig = new DownloadConfig(config);
    Optional<Proxy> proxy = downloadConfig.getProxy();

    for (String repo : downloadConfig.getAllMavenRepos()) {
      // Check the type.
      if (repo.startsWith("http:") || repo.startsWith("https://")) {
        downloaders.add(new RemoteMavenDownloader(proxy, repo));
      } else if (repo.startsWith("file:")) {
        try {
          URL url = new URL(repo);
          Preconditions.checkNotNull(url.getPath());

          downloaders.add(
              new OnDiskMavenDownloader(
                  config.resolvePathThatMayBeOutsideTheProjectFilesystem(
                      Paths.get(url.getPath()))));
        } catch (MalformedURLException e) {
          throw new HumanReadableException("Unable to determine path from %s", repo);
        }
      } else {
        downloaders.add(
            new OnDiskMavenDownloader(
                config.resolvePathThatMayBeOutsideTheProjectFilesystem(Paths.get(repo))));
      }
    }

    if (androidSdkRoot.isPresent()) {
      Path androidMavenRepo = androidSdkRoot.get().resolve("extras/android/m2repository");
      if (Files.exists(androidMavenRepo)) {
        downloaders.add(new OnDiskMavenDownloader(androidMavenRepo));
      }

      Path googleMavenRepo = androidSdkRoot.get().resolve("extras/google/m2repository");
      if (Files.exists(googleMavenRepo)) {
        downloaders.add(new OnDiskMavenDownloader(googleMavenRepo));
      }
    }

    // Add a default downloader
    // TODO(simons): Remove the maven_repo check
    Optional<String> defaultMavenRepo = downloadConfig.getMavenRepo();
    if (defaultMavenRepo.isPresent()) {
      LOG.warn("Please configure maven repos by adding them to a 'maven_repositories' " +
              "section in your buckconfig");
    }
    downloaders.add(new HttpDownloader(proxy));

    return new StackedDownloader(downloaders.build());
  }

  @Override
  public boolean fetch(BuckEventBus eventBus, URI uri, Path output) throws IOException {
    for (Downloader downloader : delegates) {
      try {
        if (downloader.fetch(eventBus, uri, output)) {
          return true;
        }
      } catch (IOException e) {
        LOG.debug(e, "Unable to download %s from %s", uri, downloader);
      }
    }

    return false;
  }
}
