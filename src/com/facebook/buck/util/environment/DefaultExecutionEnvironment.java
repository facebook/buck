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

import com.google.common.base.Optional;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class DefaultExecutionEnvironment implements ExecutionEnvironment {
  private static final long  MEGABYTE = 1024L * 1024L;
  private final Platform platform;

  public DefaultExecutionEnvironment() {
    platform = Platform.detect();
  }

  @Override
  public String getHostname() {
    String localHostname;
    try {
      localHostname = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      localHostname = "unknown";
    }
    return localHostname;
  }

  @Override
  public String getUsername() {
    return Optional.fromNullable(System.getenv("USER")).or("unknown");
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
}
