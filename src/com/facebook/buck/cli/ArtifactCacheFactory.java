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
import com.google.common.base.Supplier;

/**
 * Factory to create an {@link ArtifactCache}. In practice, this will likely be used as a one-time
 * {@link Supplier}.
 */
public interface ArtifactCacheFactory {

  public ArtifactCache newInstance(AbstractCommandOptions options);

  /**
   * Close all instances of {@link ArtifactCache} created by the factory.
   *
   * @param timeoutInSeconds time to wait for the caches to close, in seconds
   */
  public void closeCreatedArtifactCaches(int timeoutInSeconds);

}
