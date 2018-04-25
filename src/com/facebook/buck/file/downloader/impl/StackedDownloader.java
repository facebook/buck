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

import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.DownloadConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.log.Logger;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

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

  public static Downloader createFromConfig(
      BuckConfig config, ToolchainProvider toolchainProvider) {
    ImmutableList.Builder<Downloader> downloaders = ImmutableList.builder();

    DownloadConfig downloadConfig = new DownloadConfig(config);
    Optional<Proxy> proxy = downloadConfig.getProxy();
    HttpDownloader httpDownloader = new HttpDownloader(proxy);

    for (Map.Entry<String, String> kv : downloadConfig.getAllMavenRepos().entrySet()) {
      String repo = Preconditions.checkNotNull(kv.getValue());
      // Check the type.
      if (repo.startsWith("http:") || repo.startsWith("https://")) {
        String repoName = kv.getKey();
        Optional<PasswordAuthentication> credentials = downloadConfig.getRepoCredentials(repoName);
        downloaders.add(new RemoteMavenDownloader(httpDownloader, repo, credentials));
      } else if (repo.startsWith("file:")) {
        try {
          URL url = new URL(repo);
          Preconditions.checkNotNull(url.getPath());

          downloaders.add(
              new OnDiskMavenDownloader(
                  config.resolvePathThatMayBeOutsideTheProjectFilesystem(
                      Paths.get(url.getPath()))));
        } catch (FileNotFoundException e) {
          throw new HumanReadableException(
              e,
              "Error occurred when attempting to use %s "
                  + "as a local Maven repository as configured in .buckconfig.  See "
                  + "https://buckbuild.com/concept/buckconfig.html#maven_repositories for how to "
                  + "configure this setting",
              repo);
        } catch (MalformedURLException e) {
          throw new HumanReadableException("Unable to determine path from %s", repo);
        }
      } else {
        try {
          downloaders.add(
              new OnDiskMavenDownloader(
                  Preconditions.checkNotNull(
                      config.resolvePathThatMayBeOutsideTheProjectFilesystem(Paths.get(repo)))));
        } catch (FileNotFoundException e) {
          throw new HumanReadableException(
              e,
              "Error occurred when attempting to use %s "
                  + "as a local Maven repository as configured in .buckconfig.  See "
                  + "https://buckbuild.com/concept/buckconfig.html#maven_repositories for how to "
                  + "configure this setting",
              repo);
        }
      }
    }

    if (toolchainProvider.isToolchainPresent(AndroidSdkLocation.DEFAULT_NAME)) {
      AndroidSdkLocation androidSdkLocation =
          toolchainProvider.getByName(AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class);
      Path androidSdkRootPath = androidSdkLocation.getSdkRootPath();
      Path androidMavenRepo = androidSdkRootPath.resolve("extras/android/m2repository");
      try {
        downloaders.add(new OnDiskMavenDownloader(androidMavenRepo));
      } catch (FileNotFoundException e) {
        LOG.warn("Android Maven repo %s doesn't exist", androidMavenRepo.toString());
      }

      Path googleMavenRepo = androidSdkRootPath.resolve("extras/google/m2repository");
      try {
        downloaders.add(new OnDiskMavenDownloader(googleMavenRepo));
      } catch (FileNotFoundException e) {
        LOG.warn("Google Maven repo '%s' doesn't exist", googleMavenRepo.toString());
      }
    }

    // Add a default downloader
    // TODO(simons): Remove the maven_repo check
    Optional<String> defaultMavenRepo = downloadConfig.getMavenRepo();
    if (defaultMavenRepo.isPresent()) {
      LOG.warn(
          "Please configure maven repos by adding them to a 'maven_repositories' "
              + "section in your buckconfig");
    }
    downloaders.add(
        downloadConfig
            .getMaxNumberOfRetries()
            .map(retries -> (Downloader) RetryingDownloader.from(httpDownloader, retries))
            .orElse(httpDownloader));

    return new StackedDownloader(downloaders.build());
  }

  @Override
  public boolean fetch(BuckEventBus eventBus, URI uri, Path output) {
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
