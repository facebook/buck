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

package com.facebook.buck.rules;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.google.common.collect.Multimap;

import java.nio.file.Path;


/**
 * Context object that is specific to an individual {@link BuildRule}.
 * This differs from {@link BuildContext} in that a {@link BuildContext} is a context that is shared
 * by all {@link BuildRule}s whereas a new {@link BuildableContext} is created for each call to
 * {@link BuildRule#getBuildSteps(BuildContext, BuildableContext)}.
 */
public interface BuildableContext {

  /**
   * When building a {@link BuildRule}, any metadata about the output files for the rule should be
   * recorded via this method. This ensures it will be stored in the {@link ArtifactCache} along
   * with the output.
   * <p>
   * If a value for the specified {@code key} has already been set, an exception will be thrown.
   * (If we discover a good reason for this to be allowed later, we can always relax this
   * constraint.)
   */
  public void addMetadata(String key, String value);

  /**
   * @see BuildInfoRecorder#addMetadata(String, Iterable)
   */
  public void addMetadata(String key, Iterable<String> values);

  /**
   * @see BuildInfoRecorder#addMetadata(String, Multimap)
   */
  public void addMetadata(String key, Multimap<String, String> values);

  /**
   * @see BuildInfoRecorder#recordArtifact(Path)
   */
  public void recordArtifact(Path pathToArtifact);

}
