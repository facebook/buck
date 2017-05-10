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

package com.facebook.buck.event.listener;

import static java.lang.Integer.parseInt;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.config.ConfigView;
import java.net.URI;
import java.util.Optional;

public class ChromeTraceBuckConfig implements ConfigView<BuckConfig> {
  private static final String DEFAULT_MAX_TRACES = "25";

  private static final String LOG_SECTION = "log";

  private final BuckConfig delegate;

  public static ChromeTraceBuckConfig of(BuckConfig delegate) {
    return new ChromeTraceBuckConfig(delegate);
  }

  private ChromeTraceBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
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

  public Optional<URI> getTraceUploadUri() {
    if (!getShouldUploadBuildTraces()) {
      return Optional.empty();
    }
    return delegate.getUrl(LOG_SECTION, "trace_upload_uri");
  }

  private boolean getShouldUploadBuildTraces() {
    return delegate.getBooleanValue("experiments", "upload_build_traces", false);
  }

  @Override
  public BuckConfig getDelegate() {
    return delegate;
  }
}
