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

package com.facebook.buck.cli;

import com.facebook.buck.rules.ArtifactCache;
import com.google.common.base.Preconditions;

import java.io.IOException;

/**
 * An implementation of {@link ArtifactCacheFactory} used for testing that always returns the
 * instance of {@link ArtifactCache} passed to its constructor when its
 * {@link #newInstance(BuckConfig, boolean)} method is invoked.
 */
public class InstanceArtifactCacheFactory implements ArtifactCacheFactory {

  private final ArtifactCache artifactCache;

  public InstanceArtifactCacheFactory(ArtifactCache artifactCache) {
    this.artifactCache = Preconditions.checkNotNull(artifactCache);
  }

  @Override
  public ArtifactCache newInstance(BuckConfig buckConfig, boolean noop) {
    return artifactCache;
  }

  @Override
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void closeCreatedArtifactCaches(int timeoutInSeconds) {
    // Ignore timeout for tests.
    try {
      artifactCache.close();
    } catch (IOException e) {
      // Ignore the exception and move on.
    }
  }

}
