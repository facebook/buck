/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.parser.cache;

import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import java.nio.file.Path;
import java.util.Optional;

/** This is the main interface for interacting with the cache. */
public interface ParserCache {

  /**
   * Stores a {@link BuildFileManifest} object to the cache's storage(s).
   *
   * @param buildFile the {@link Path} to the build spec associated with the cell being built.
   * @param buildFileManifest the {@link BuildFileManifest} to store in the cache.
   */
  void storeBuildFileManifest(Path buildFile, BuildFileManifest buildFileManifest);

  /**
   * Gets a cached {@link BuildFileManifest} if one is available, based on passed in parameters.
   *
   * @param buildFile the {@link Path} to the build spec associated with the cell being built.
   * @param parser the {@link ProjectBuildFileParser} that is querying the cache.
   * @return a {@link BuildFileManifest} if the operation is successful. In case of failure an
   */
  Optional<BuildFileManifest> getBuildFileManifest(Path buildFile, ProjectBuildFileParser parser);
}
