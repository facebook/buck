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
import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Objects;
import java.util.Optional;
import org.immutables.value.Value;

@BuckStyleValue
public abstract class BuildEnvironmentDescription {
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

    return of(
        executionEnvironment.getUsername(),
        executionEnvironment.getHostname(),
        executionEnvironment.getPlatform().getPrintableName().toLowerCase().replace(' ', '_'),
        executionEnvironment.getAvailableCores(),
        executionEnvironment.getTotalMemory(),
        buckDirty,
        System.getProperty("buck.git_commit", "unknown"),
        Objects.requireNonNull(StandardSystemProperty.JAVA_VM_VERSION.value()),
        cacheModes,
        extraData);
  }

  public static BuildEnvironmentDescription of(
      String user,
      String hostname,
      String os,
      int availableCores,
      long systemMemory,
      Optional<Boolean> buckDirty,
      String buckCommit,
      String javaVersion,
      ImmutableList<String> cacheModes,
      ImmutableMap<String, String> extraData) {
    return of(
        user,
        hostname,
        os,
        availableCores,
        systemMemory,
        buckDirty,
        buckCommit,
        javaVersion,
        cacheModes,
        extraData,
        PROTOCOL_VERSION,
        BuckBuildType.CURRENT_BUCK_BUILD_TYPE.get().toString());
  }

  public static BuildEnvironmentDescription of(
      String user,
      String hostname,
      String os,
      int availableCores,
      long systemMemory,
      Optional<Boolean> buckDirty,
      String buckCommit,
      String javaVersion,
      int jsonProtocolVersion) {
    return of(
        user,
        hostname,
        os,
        availableCores,
        systemMemory,
        buckDirty,
        buckCommit,
        javaVersion,
        ImmutableList.of(),
        ImmutableMap.of(),
        jsonProtocolVersion,
        BuckBuildType.CURRENT_BUCK_BUILD_TYPE.get().toString());
  }

  public static BuildEnvironmentDescription of(
      String user,
      String hostname,
      String os,
      int availableCores,
      long systemMemory,
      Optional<Boolean> buckDirty,
      String buckCommit,
      String javaVersion,
      ImmutableList<String> cacheModes,
      ImmutableMap<String, String> extraData,
      int jsonProtocolVersion,
      String buildType) {
    return ImmutableBuildEnvironmentDescription.of(
        user,
        hostname,
        os,
        availableCores,
        systemMemory,
        buckDirty,
        buckCommit,
        javaVersion,
        cacheModes,
        extraData,
        jsonProtocolVersion,
        buildType);
  }
}
