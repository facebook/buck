/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class TestArtifactCaches {
  private TestArtifactCaches() {}

  public static ArtifactCache createDirCacheForTest(Path filesystemRoot, Path cacheDir)
      throws IOException {
    return new DirArtifactCache(
        "dir",
        TestProjectFilesystems.createProjectFilesystem(filesystemRoot),
        cacheDir,
        CacheReadMode.READWRITE,
        Optional.empty(),
        MoreExecutors.newDirectExecutorService());
  }
}
