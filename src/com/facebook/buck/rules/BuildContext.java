/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.immutables.DeprecatedBuckStyleImmutable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;

import org.immutables.value.Value;

import java.util.Map;

@Value.Immutable
@DeprecatedBuckStyleImmutable
@SuppressWarnings("deprecation")
public abstract class BuildContext {

  public abstract ActionGraph getActionGraph();

  protected abstract Clock getClock();
  public abstract ArtifactCache getArtifactCache();
  public abstract JavaPackageFinder getJavaPackageFinder();
  public abstract BuckEventBus getEventBus();

  @Value.Default
  public Supplier<AndroidPlatformTarget> getAndroidPlatformTargetSupplier() {
    return AndroidPlatformTarget.EXPLODING_ANDROID_PLATFORM_TARGET_SUPPLIER;
  }

  protected abstract BuildId getBuildId();
  protected abstract ObjectMapper getObjectMapper();
  protected abstract Map<String, String> getEnvironment();

  @Value.Default
  public boolean isKeepGoing() {
    return false;
  }

  @Value.Default
  public boolean shouldReportAbsolutePaths() {
    return false;
  }

  /**
   * Creates an {@link OnDiskBuildInfo}.
   * <p>
   * This method should be visible to {@link AbstractBuildRule}, but not {@link BuildRule}s
   * in general.
   */
  OnDiskBuildInfo createOnDiskBuildInfoFor(BuildTarget target, ProjectFilesystem filesystem) {
    return new DefaultOnDiskBuildInfo(target, filesystem, getObjectMapper());
  }

  /**
   * Creates an {@link BuildInfoRecorder}.
   * <p>
   * This method should be visible to {@link AbstractBuildRule}, but not {@link BuildRule}s
   * in general.
   */
  BuildInfoRecorder createBuildInfoRecorder(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return new BuildInfoRecorder(
        buildTarget,
        filesystem,
        getClock(),
        getBuildId(),
        getObjectMapper(),
        ImmutableMap.copyOf(getEnvironment()));
  }

  public void logBuildInfo(String format, Object... args) {
    getEventBus().post(ConsoleEvent.fine(format, args));
  }

  public void logError(Throwable error, String msg, Object... formatArgs) {
    getEventBus().post(ThrowableConsoleEvent.create(error, msg, formatArgs));
  }

  public void logError(String msg, Object... formatArgs) {
    getEventBus().post(ConsoleEvent.severe(msg, formatArgs));
  }
}
