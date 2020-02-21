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

package com.facebook.buck.support.build.report;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Optional;
import org.immutables.value.Value;

/** {@link ConfigView} for BuildReport Configuration */
@BuckStyleValue
public abstract class BuildReportConfig implements ConfigView<BuckConfig> {

  private static final String BUILD_REPORT_SECTION = "build_report";
  private static final String ENABLED_FIELD = "enable_build_report";
  private static final String ENDPOINT_URL_FIELD = "endpoint_url";
  private static final String ENDPOINT_TIMEOUT_MS_FIELD = "endpoint_timeout_ms";
  private static final String REMOVE_OUTPUT_FIELD = "remove_output";

  private static final long DEFAULT_ENDPOINT_TIMEOUT_MS = 30 * 1000;

  private static final boolean ENABLED_DEFAULT = false;

  private static final boolean REMOVE_OUTPUT_FIELD_DEFAULT = false;

  @Override
  public abstract BuckConfig getDelegate();

  public static BuildReportConfig of(BuckConfig delegate) {
    return ImmutableBuildReportConfig.of(delegate);
  }

  @Value.Lazy
  public boolean getEnabled() {
    return getDelegate().getBooleanValue(BUILD_REPORT_SECTION, ENABLED_FIELD, ENABLED_DEFAULT);
  }

  @Value.Lazy
  public Optional<URL> getEndpointUrl() throws HumanReadableException {
    Optional<URI> uri = getDelegate().getUrl(BUILD_REPORT_SECTION, ENDPOINT_URL_FIELD);
    if (uri.isPresent()) {
      try {
        return Optional.of(uri.get().toURL());
      } catch (MalformedURLException e) {
        throw new HumanReadableException(e, "Build Report Endpoint URL is not a valid URL");
      }
    }
    return Optional.empty();
  }

  @Value.Lazy
  public long getEndpointTimeoutMs() {
    return getDelegate()
        .getLong(BUILD_REPORT_SECTION, ENDPOINT_TIMEOUT_MS_FIELD)
        .orElse(DEFAULT_ENDPOINT_TIMEOUT_MS);
  }

  @Value.Lazy
  public boolean getRemoveOutput() {
    return getDelegate()
        .getBooleanValue(BUILD_REPORT_SECTION, REMOVE_OUTPUT_FIELD, REMOVE_OUTPUT_FIELD_DEFAULT);
  }
}
