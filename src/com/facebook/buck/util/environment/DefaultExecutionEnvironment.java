/*
 * Copyright 2013-present Facebook, Inc.
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
package com.facebook.buck.util.environment;

import com.facebook.buck.util.network.HostnameFetching;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.sun.management.OperatingSystemMXBean;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Properties;

public class DefaultExecutionEnvironment implements ExecutionEnvironment {
  private static final long  MEGABYTE = 1024L * 1024L;

  // Buck's own integration tests will run with this system property
  // set to false.
  //
  // Otherwise, we would need to add libjcocoa.dylib to
  // java.library.path, which could interfere with external Java
  // tests' own C library dependencies.
  private static final boolean ENABLE_OBJC = Boolean.getBoolean("buck.enable_objc");
  private final Platform platform;
  private final ImmutableMap<String, String> environment;
  private final Properties properties;

  public DefaultExecutionEnvironment(
      ImmutableMap<String, String> environment,
      Properties properties) {
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
    return getenv("USER", getProperty("user.name", "<unknown>"));
  }

  @Override
  public int getAvailableCores() {
    return Runtime.getRuntime().availableProcessors();
  }

  @Override
  public long getTotalMemoryInMb() {
    OperatingSystemMXBean osBean =
        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    return osBean.getTotalPhysicalMemorySize() / MEGABYTE;
  }

  @Override
  public Platform getPlatform() {
    return platform;
  }

  @Override
  public Optional<String> getWifiSsid() throws InterruptedException {
    // TODO(rowillia): Support Linux and Windows.
    if (ENABLE_OBJC) {
      return MacWifiSsidFinder.findCurrentSsid();
    }
    return Optional.absent();
  }

  @Override
  public String getenv(String key, String defaultValue) {
    String value = environment.get(key);
    return value != null ? value : defaultValue;
  }

  @Override
  public String getProperty(String key, String defaultValue) {
    return properties.getProperty(key, defaultValue);
  }
}
