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

import java.io.Closeable;
import java.io.File;

public interface ArtifactCache extends Closeable {
  /**
   * Fetch a cached artifact, keyed by ruleKey, save the artifact to path specified by output, and
   * return true on success.
   *
   * @param ruleKey cache fetch key
   * @param output path to store artifact to
   * @return whether it was a {@link CacheResult#MISS} (indicating a failure) or some type of hit.
   */
  public CacheResult fetch(RuleKey ruleKey, File output) throws InterruptedException;

  /**
   * Store the artifact at path specified by output to cache, such that it can later be fetched
   * using ruleKey as the lookup key.  If any internal errors occur, fail silently and continue
   * execution.
   * <p>
   * This is a noop if {@link #isStoreSupported()} returns {@code false}.
   *
   * @param ruleKey cache store key
   * @param output path to read artifact from
   */
  public void store(RuleKey ruleKey, File output) throws InterruptedException;

  /**
   * This method must return the same value over the lifetime of this object.
   * @return whether this{@link ArtifactCache} supports storing artifacts.
   */
  public boolean isStoreSupported();
}
