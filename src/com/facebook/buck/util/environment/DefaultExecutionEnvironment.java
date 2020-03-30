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

import com.facebook.buck.util.network.hostname.HostnameFetching;
import com.google.common.collect.ImmutableMap;
import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Optional;
import java.util.Properties;

public class DefaultExecutionEnvironment implements ExecutionEnvironment {
  private final Platform platform;
  private final ImmutableMap<String, String> environment;
  private final Properties properties;

  public DefaultExecutionEnvironment(
      ImmutableMap<String, String> environment, Properties properties) {
    this.platform = Platform.detect();
    this.environment = environment;
    this.properties = properties;
  }

  @Override
  public String getHostname() {
    String localHostname;
    try {
      localHostname = HostnameFetching.getHostname();
    } catch (IOException e) {
      localHostname = "unknown";
    }
    return localHostname;
  }

  @Override
  public String getUsername() {
    return getenv("USER").orElse(properties.getProperty("user.name", "<unknown>"));
  }

  @Override
  public int getAvailableCores() {
    return Runtime.getRuntime().availableProcessors();
  }

  @Override
  public long getTotalMemory() {
    OperatingSystemMXBean osBean =
        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    return osBean.getTotalPhysicalMemorySize();
  }

  @Override
  public Platform getPlatform() {
    return platform;
  }

  @Override
  public Network getLikelyActiveNetwork() {
    return NetworkInfo.getLikelyActiveNetwork();
  }

  @Override
  public Optional<String> getWifiSsid() {
    return NetworkInfo.getWifiSsid();
  }

  @Override
  public Optional<String> getenv(String key) {
    return Optional.ofNullable(environment.get(key));
  }
}
