/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.util.TriState;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractBuildEnvironmentDescription {
  public static final int PROTOCOL_VERSION = 1;

  public abstract String getUser();
  public abstract String getHostname();
  public abstract String getOs();
  public abstract int getAvailableCores();
  public abstract long getSystemMemoryMb();
  public abstract TriState getBuckDirty();
  public abstract String getBuckCommit();
  public abstract String getJavaVersion();
  public abstract ImmutableList<String> getCacheModes();
  public abstract ImmutableMap<String, String> getExtraData();
  @Value.Default
  public int getJsonProtocolVersion() {
    return PROTOCOL_VERSION;
  }

  public static BuildEnvironmentDescription of(
      ExecutionEnvironment executionEnvironment,
      ImmutableList<String> cacheModes,
      ImmutableMap<String, String> extraData) {
    TriState buckDirty;
    String dirty = executionEnvironment.getProperty("buck.git_dirty", "unknown");
    if (dirty.equals("1")) {
      buckDirty = TriState.TRUE;
    } else if (dirty.equals("0")) {
      buckDirty = TriState.FALSE;
    } else {
      buckDirty = TriState.UNSPECIFIED;
    }

    return BuildEnvironmentDescription.builder()
        .setUser(executionEnvironment.getUsername())
        .setHostname(executionEnvironment.getHostname())
        .setOs(
            executionEnvironment
                .getPlatform().getPrintableName().toLowerCase().replace(' ', '_'))
        .setAvailableCores(executionEnvironment.getAvailableCores())
        .setSystemMemoryMb(executionEnvironment.getTotalMemoryInMb())
        .setBuckDirty(buckDirty)
        .setBuckCommit(executionEnvironment.getProperty("buck.git_commit", "unknown"))
        .setJavaVersion(StandardSystemProperty.JAVA_VM_VERSION.value())
        .setCacheModes(cacheModes)
        .setExtraData(extraData)
        .build();
  }
}
