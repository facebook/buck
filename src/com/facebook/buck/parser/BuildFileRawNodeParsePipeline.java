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

package com.facebook.buck.parser;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.google.common.util.concurrent.ListeningExecutorService;

/** A pipeline that provides a {@link BuildFileManifest} for a given build file. */
public class BuildFileRawNodeParsePipeline extends GenericFileParsePipeline<BuildFileManifest> {

  public BuildFileRawNodeParsePipeline(
      PipelineNodeCache<AbsPath, BuildFileManifest> cache,
      ProjectBuildFileParserPool projectBuildFileParserPool,
      ListeningExecutorService executorService,
      BuckEventBus eventBus,
      Watchman watchman) {
    super(cache, projectBuildFileParserPool, executorService, eventBus, watchman);
  }
}
