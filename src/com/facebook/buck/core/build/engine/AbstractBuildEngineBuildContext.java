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
package com.facebook.buck.core.build.engine;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfoRecorder;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfoStore;
import com.facebook.buck.core.build.engine.buildinfo.DefaultOnDiskBuildInfo;
import com.facebook.buck.core.build.engine.buildinfo.OnDiskBuildInfo;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.util.timing.Clock;
import com.google.common.collect.ImmutableMap;
import org.immutables.value.Value;

/** Per-build context used by {@link BuildEngine}. */
@Value.Immutable(copy = true)
@BuckStyleImmutable
abstract class AbstractBuildEngineBuildContext {
  /**
   * {@code BuildContext} used by various rules to generate {@link com.facebook.buck.step.Step}s.
   */
  public abstract BuildContext getBuildContext();

  public abstract ArtifactCache getArtifactCache();

  protected abstract Clock getClock();

  protected abstract BuildId getBuildId();

  protected abstract ImmutableMap<String, String> getEnvironment();

  @Value.Default
  public boolean isKeepGoing() {
    return false;
  }

  /**
   * Creates an {@link OnDiskBuildInfo}.
   *
   * <p>This method should be visible to {@link AbstractBuildRule}, but not {@link BuildRule}s in
   * general.
   */
  public OnDiskBuildInfo createOnDiskBuildInfoFor(
      BuildTarget target, ProjectFilesystem filesystem, BuildInfoStore buildInfoStore) {
    return new DefaultOnDiskBuildInfo(target, filesystem, buildInfoStore);
  }

  /**
   * Creates an {@link BuildInfoRecorder}.
   *
   * <p>This method should be visible to {@link AbstractBuildRule}, but not {@link BuildRule}s in
   * general.
   */
  public BuildInfoRecorder createBuildInfoRecorder(
      BuildTarget buildTarget, ProjectFilesystem filesystem, BuildInfoStore buildInfoStore) {
    return new BuildInfoRecorder(
        buildTarget,
        filesystem,
        buildInfoStore,
        getClock(),
        getBuildId(),
        ImmutableMap.copyOf(getEnvironment()));
  }

  public final BuckEventBus getEventBus() {
    return getBuildContext().getEventBus();
  }
}
