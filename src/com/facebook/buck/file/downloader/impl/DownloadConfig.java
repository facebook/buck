/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.util.log.Logger;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.util.Optional;
import java.util.OptionalInt;

public class DownloadConfig {
  private static final Logger LOG = Logger.get(DownloadConfig.class);
  private final BuckConfig delegate;

  public DownloadConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public Optional<Proxy> getProxy() {

    Optional<String> proxyHost = delegate.getValue("download", "proxy_host");
    Optional<Long> proxyPort = delegate.getLong("download", "proxy_port");
    String proxyType = delegate.getValue("download", "proxy_type").orElse("HTTP");

    LOG.debug("Got proxy: " + proxyHost + " " + proxyPort + " from " + delegate);
    if (proxyHost.isPresent() && proxyPort.isPresent()) {
      Proxy.Type type = Proxy.Type.valueOf(proxyType);
      long port = proxyPort.get();
      Proxy p = new Proxy(type, new InetSocketAddress(proxyHost.get(), (int) port));
      return Optional.of(p);
    }
    return Optional.empty();
  }

  public Optional<String> getMavenRepo() {
    return delegate.getValue("download", "maven_repo");
  }

  public ImmutableMap<String, String> getAllMavenRepos() {
    ImmutableSortedMap.Builder<String, String> repos = ImmutableSortedMap.naturalOrder();
    repos.putAll(delegate.getEntriesForSection("maven_repositories"));

    Optional<String> defaultRepo = getMavenRepo();
    if (defaultRepo.isPresent()) {
      repos.put(defaultRepo.get(), defaultRepo.get());
    }

    return repos.build();
  }

  public boolean isDownloadAtRuntimeOk() {
    return delegate.getBooleanValue("download", "in_build", false);
  }

  public Optional<PasswordAuthentication> getRepoCredentials(String repo) {
    Optional<String> user = delegate.getValue("credentials", repo + "_user");
    Optional<String> password = delegate.getValue("credentials", repo + "_pass");
    if (!user.isPresent() || !password.isPresent()) {
      return Optional.empty();
    }

    return Optional.of(new PasswordAuthentication(user.get(), password.get().toCharArray()));
  }

  public OptionalInt getMaxNumberOfRetries() {
    return delegate.getInteger("download", "max_number_of_retries");
  }
}
