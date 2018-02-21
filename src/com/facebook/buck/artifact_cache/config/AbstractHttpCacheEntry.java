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

package com.facebook.buck.artifact_cache.config;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.net.URI;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractHttpCacheEntry {
  public abstract URI getUrl();

  public abstract int getConnectTimeoutSeconds();

  public abstract int getReadTimeoutSeconds();

  public abstract int getWriteTimeoutSeconds();

  public abstract ImmutableMap<String, String> getReadHeaders();

  public abstract ImmutableMap<String, String> getWriteHeaders();

  public abstract CacheReadMode getCacheReadMode();

  protected abstract ImmutableSet<String> getBlacklistedWifiSsids();

  public abstract String getErrorMessageFormat();

  public abstract int getErrorMessageLimit();

  public abstract Optional<Long> getMaxStoreSize();

  // We're connected to a wifi hotspot that has been explicitly blacklisted from connecting to
  // a distributed cache.
  public boolean isWifiUsableForDistributedCache(Optional<String> currentWifiSsid) {
    return !(currentWifiSsid.isPresent()
        && getBlacklistedWifiSsids().contains(currentWifiSsid.get()));
  }
}
