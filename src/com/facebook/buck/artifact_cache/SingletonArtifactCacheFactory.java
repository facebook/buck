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
package com.facebook.buck.artifact_cache;

import com.facebook.buck.core.config.BuckConfig;

public class SingletonArtifactCacheFactory implements ArtifactCacheFactory {
  private final ArtifactCache artifactCache;

  public SingletonArtifactCacheFactory(ArtifactCache artifactCache) {
    this.artifactCache = artifactCache;
  }

  @Override
  public ArtifactCache newInstance() {
    return artifactCache;
  }

  @Override
  public ArtifactCache newInstance(
      boolean distributedBuildModeEnabled, boolean isDownloadHeavyBuild) {
    return artifactCache;
  }

  @Override
  public ArtifactCache remoteOnlyInstance(
      boolean distributedBuildModeEnabled, boolean isDownloadHeavyBuild) {
    return artifactCache;
  }

  @Override
  public ArtifactCache localOnlyInstance(
      boolean distributedBuildModeEnabled, boolean isDownloadHeavyBuild) {
    return artifactCache;
  }

  @Override
  public ArtifactCacheFactory cloneWith(BuckConfig newConfig) {
    return new SingletonArtifactCacheFactory(artifactCache);
  }
}
