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

package com.facebook.buck.event.chrome_trace;

import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.event.LogUploadMode;
import com.facebook.buck.util.environment.NetworkInfo;
import com.google.common.collect.ImmutableSet;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Chrome traces configuration */
@BuckStyleValue
public abstract class ChromeTraceBuckConfig implements ConfigView<BuckConfig> {

  private static final String LOG_SECTION = "log";

  private static final int DEFAULT_UPLOAD_TRACE_TIMEOUT_SECONDS =
      (int) TimeUnit.MINUTES.toSeconds(1);
  private static final int DEFAULT_MAX_TRACES = 25;

  @Override
  public abstract BuckConfig getDelegate();

  abstract ImmutableSet<String> getBlocklistedWifiSsids();

  public static ChromeTraceBuckConfig of(BuckConfig delegate) {
    return ImmutableChromeTraceBuckConfig.ofImpl(
        delegate, delegate.getView(ArtifactCacheBuckConfig.class).getBlacklistedWifiSsids());
  }

  public int getMaxTraces() {
    return getDelegate().getInteger(LOG_SECTION, "max_traces").orElse(DEFAULT_MAX_TRACES);
  }

  public boolean isChromeTraceCreationEnabled() {
    return getDelegate().getBooleanValue(LOG_SECTION, "chrome_trace_generation", true);
  }

  public boolean hasToCompressTraces() {
    return getDelegate().getBooleanValue(LOG_SECTION, "compress_traces", false);
  }

  /** Get URL to upload trace if the config is enabled. */
  public Optional<URI> getTraceUploadUriIfEnabled() {
    return shouldUploadBuildTraces() ? getTraceUploadUri() : Optional.empty();
  }

  /** Get URL to upload trace. */
  public Optional<URI> getTraceUploadUri() {
    return getDelegate().getUrl(LOG_SECTION, "trace_upload_uri");
  }

  /** Returns whether and when to upload logs. */
  public LogUploadMode getLogUploadMode() {
    return getDelegate()
        .getEnum(LOG_SECTION, "log_upload_mode", LogUploadMode.class)
        .orElse(LogUploadMode.NEVER);
  }

  public int getMaxUploadTimeoutInSeconds() {
    return getDelegate()
        .getInteger(LOG_SECTION, "max_upload_timeout_sec")
        .orElse(DEFAULT_UPLOAD_TRACE_TIMEOUT_SECONDS);
  }

  private boolean shouldUploadBuildTraces() {
    if (!getDelegate().getBooleanValue("experiments", "upload_build_traces", false)) {
      return false;
    }

    Optional<String> wifiSsid = NetworkInfo.getWifiSsid();
    // Either we don't know how to detect the SSID, or we're wired. Either way, upload
    return wifiSsid.map(ssid -> !getBlocklistedWifiSsids().contains(ssid)).orElse(true);
  }
}
