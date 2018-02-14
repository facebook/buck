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

package com.facebook.buck.event.chrome_trace;

import static java.lang.Integer.parseInt;

import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.ConfigView;
import com.facebook.buck.event.LogUploadMode;
import com.facebook.buck.util.environment.NetworkInfo;
import java.net.URI;
import java.util.Optional;

public class ChromeTraceBuckConfig implements ConfigView<BuckConfig> {
  private static final String DEFAULT_MAX_TRACES = "25";

  private static final String LOG_SECTION = "log";

  private final BuckConfig delegate;
  private final ArtifactCacheBuckConfig artifactCacheBuckConfig;

  public static ChromeTraceBuckConfig of(BuckConfig delegate) {
    return new ChromeTraceBuckConfig(delegate);
  }

  private ChromeTraceBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
    this.artifactCacheBuckConfig = delegate.getView(ArtifactCacheBuckConfig.class);
  }

  public int getMaxTraces() {
    return parseInt(delegate.getValue(LOG_SECTION, "max_traces").orElse(DEFAULT_MAX_TRACES));
  }

  public boolean isChromeTraceCreationEnabled() {
    return delegate.getBooleanValue(LOG_SECTION, "chrome_trace_generation", true);
  }

  public boolean getCompressTraces() {
    return delegate.getBooleanValue(LOG_SECTION, "compress_traces", false);
  }

  /** Get URL to upload trace if the config is enabled. */
  public Optional<URI> getTraceUploadUriIfEnabled() {
    if (!getShouldUploadBuildTraces()) {
      return Optional.empty();
    }
    return getTraceUploadUri();
  }

  /** Get URL to upload trace. */
  public Optional<URI> getTraceUploadUri() {
    return delegate.getUrl(LOG_SECTION, "trace_upload_uri");
  }

  /** Returns whether and when to upload logs. */
  public LogUploadMode getLogUploadMode() {
    return delegate
        .getEnum("log", "log_upload_mode", LogUploadMode.class)
        .orElse(LogUploadMode.NEVER);
  }

  private boolean getShouldUploadBuildTraces() {
    if (!delegate.getBooleanValue("experiments", "upload_build_traces", false)) {
      return false;
    }

    Optional<String> wifiSsid = NetworkInfo.getWifiSsid();
    if (!wifiSsid.isPresent()) {
      // Either we don't know how to detect the SSID, or we're wired. Either way, upload
      return true;
    }

    boolean blacklisted =
        artifactCacheBuckConfig.getBlacklistedWifiSsids().contains(wifiSsid.get());
    return !blacklisted;
  }

  @Override
  public BuckConfig getDelegate() {
    return delegate;
  }
}
