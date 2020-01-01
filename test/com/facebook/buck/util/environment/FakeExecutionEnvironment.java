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

package com.facebook.buck.util.environment;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.Map;
import java.util.Optional;

/** Test utility implementation of {@link ExecutionEnvironment} based on an immutable value type. */
@BuckStyleValue
public abstract class FakeExecutionEnvironment implements ExecutionEnvironment {
  @Override
  public abstract String getHostname();

  @Override
  public abstract String getUsername();

  @Override
  public abstract int getAvailableCores();

  @Override
  public abstract long getTotalMemory();

  @Override
  public abstract Platform getPlatform();

  @Override
  public abstract Network getLikelyActiveNetwork();

  @Override
  public abstract Optional<String> getWifiSsid();

  public abstract Map<String, String> getEnvironment();

  @Override
  public Optional<String> getenv(String key) {
    return Optional.ofNullable(getEnvironment().get(key));
  }

  public static FakeExecutionEnvironment of(
      String hostname,
      String username,
      int availableCores,
      long totalMemory,
      Platform platform,
      Network likelyActiveNetwork,
      Optional<String> wifiSsid,
      Map<String, ? extends String> environment) {
    return ImmutableFakeExecutionEnvironment.of(
        hostname,
        username,
        availableCores,
        totalMemory,
        platform,
        likelyActiveNetwork,
        wifiSsid,
        environment);
  }
}
