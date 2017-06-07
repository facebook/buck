/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.doctor.config;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.config.ConfigView;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable(builder = false, copy = false)
@BuckStyleImmutable
abstract class AbstractDoctorConfig implements ConfigView<BuckConfig> {

  public static final String DOCTOR_SECTION = "doctor";
  public static final String URL_FIELD = "endpoint_url";
  public static final String DOCTOR_EXTRA_ARGS_FIELD = "extra_request_args";

  public static final long HTTP_TIMEOUT_MS = 15 * 1000;

  @Value.Parameter
  public long getHttpTimeoutMs() {
    return getDelegate().getLong(DOCTOR_SECTION, "http_timeout_ms").orElse(HTTP_TIMEOUT_MS);
  }

  @Value.Parameter
  public Optional<String> getEndpointUrl() {
    return getDelegate().getValue(DOCTOR_SECTION, URL_FIELD);
  }

  @Value.Parameter
  public ImmutableMap<String, String> getExtraRequestArgs() {
    return getDelegate().getMap(DOCTOR_SECTION, DOCTOR_EXTRA_ARGS_FIELD);
  }
}
