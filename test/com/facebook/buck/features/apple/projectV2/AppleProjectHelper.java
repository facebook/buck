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

package com.facebook.buck.features.apple.projectV2;

import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Test helper class configuring `buck project` for Apple development. */
public class AppleProjectHelper {

  static Path getBuildScriptPath(ProjectFilesystem filesystem) throws IOException {
    Path path = Paths.get("build_script.sh");
    filesystem.touch(path);
    return path;
  }

  static BuckConfig createDefaultBuckConfig(ProjectFilesystem filesystem) throws IOException {
    Path path = getBuildScriptPath(filesystem);
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    AppleConfig.APPLE_SECTION,
                    ImmutableMap.of(AppleConfig.BUILD_SCRIPT, path.toString())))
            .setFilesystem(filesystem)
            .build();

    return buckConfig;
  }

  static AppleConfig createDefaultAppleConfig(ProjectFilesystem filesystem) throws IOException {
    BuckConfig buckConfig = createDefaultBuckConfig(filesystem);
    return buckConfig.getView(AppleConfig.class);
  }
}
