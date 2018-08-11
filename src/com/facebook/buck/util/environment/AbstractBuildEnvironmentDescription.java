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

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractBuildEnvironmentDescription {
  public static final int PROTOCOL_VERSION = 1;

  public abstract String getUser();

  public abstract String getHostname();

  public abstract String getOs();

  public abstract int getAvailableCores();

  public abstract long getSystemMemory();

  public abstract Optional<Boolean> getBuckDirty();

  public abstract String getBuckCommit();

  public abstract String getJavaVersion();

  public abstract ImmutableList<String> getCacheModes();

  public abstract ImmutableMap<String, String> getExtraData();

  @Value.Default
  public int getJsonProtocolVersion() {
    return PROTOCOL_VERSION;
  }

  @Value.Default
  public String getBuildType() {
    return BuckBuildType.CURRENT_BUCK_BUILD_TYPE.get().toString();
  }

  public static BuildEnvironmentDescription of(
      ExecutionEnvironment executionEnvironment,
      ImmutableList<String> cacheModes,
      ImmutableMap<String, String> extraData) {
    Optional<Boolean> buckDirty;
    String dirty = System.getProperty("buck.git_dirty", "unknown");
    if (dirty.equals("1")) {
      buckDirty = Optional.of(true);
    } else if (dirty.equals("0")) {
      buckDirty = Optional.of(false);
    } else {
      buckDirty = Optional.empty();
    }

    return BuildEnvironmentDescription.builder()
        .setUser(executionEnvironment.getUsername())
        .setHostname(executionEnvironment.getHostname())
        .setOs(
            executionEnvironment.getPlatform().getPrintableName().toLowerCase().replace(' ', '_'))
        .setAvailableCores(executionEnvironment.getAvailableCores())
        .setSystemMemory(executionEnvironment.getTotalMemory())
        .setBuckDirty(buckDirty)
        .setBuckCommit(System.getProperty("buck.git_commit", "unknown"))
        .setJavaVersion(StandardSystemProperty.JAVA_VM_VERSION.value())
        .setCacheModes(cacheModes)
        .setExtraData(extraData)
        .build();
  }
}
