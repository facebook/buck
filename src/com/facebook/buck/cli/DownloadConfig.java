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

package com.facebook.buck.cli;

import com.facebook.buck.log.Logger;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.net.InetSocketAddress;
import java.net.Proxy;

public class DownloadConfig {
  private static final Logger LOG = Logger.get(DownloadConfig.class);
  private final BuckConfig delegate;

  public DownloadConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public Optional<Proxy> getProxy() {

    Optional<String> proxyHost = delegate.getValue("download", "proxy_host");
    Optional<Long> proxyPort = delegate.getLong("download", "proxy_port");
    String proxyType = delegate.getValue("download", "proxy_type").or("HTTP");

    LOG.debug("Got proxy: " + proxyHost + " " + proxyPort + " from " + delegate);
    if (proxyHost.isPresent() && proxyPort.isPresent()) {
      Proxy.Type type = Proxy.Type.valueOf(proxyType);
      long port = proxyPort.get();
      Proxy p = new Proxy(type, new InetSocketAddress(proxyHost.get(), (int) port));
      return Optional.of(p);
    }
    return Optional.absent();
  }

  public Optional<String> getMavenRepo() {
    return delegate.getValue("download", "maven_repo");
  }

  public ImmutableSet<String> getAllMavenRepos() {
    ImmutableSortedSet.Builder<String> repos = ImmutableSortedSet.naturalOrder();
    repos.addAll(delegate.getEntriesForSection("maven_repositories").values());

    Optional<String> defaultRepo = getMavenRepo();
    if (defaultRepo.isPresent()) {
      repos.add(defaultRepo.get());
    }

    return repos.build();
  }

  public boolean isDownloadAtRuntimeOk() {
    return delegate.getBooleanValue("download", "in_build", false);
  }
}
