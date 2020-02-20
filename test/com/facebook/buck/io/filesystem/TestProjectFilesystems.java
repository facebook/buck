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

package com.facebook.buck.io.filesystem;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.util.config.Config;
import java.nio.file.Path;

public class TestProjectFilesystems {

  public static final boolean BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH_FOR_TEST = true;

  private TestProjectFilesystems() {}

  public static DefaultProjectFilesystem createProjectFilesystem(Path root, Config config) {
    return new DefaultProjectFilesystemFactory()
        .createProjectFilesystem(
            CanonicalCellName.rootCell(),
            AbsPath.of(root),
            config,
            BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH_FOR_TEST);
  }

  public static DefaultProjectFilesystem createProjectFilesystem(Path root) {
    return new DefaultProjectFilesystemFactory()
        .createProjectFilesystem(
            CanonicalCellName.rootCell(),
            AbsPath.of(root),
            BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH_FOR_TEST);
  }

  public static DefaultProjectFilesystem createProjectFilesystem(AbsPath root) {
    return createProjectFilesystem(root.getPath());
  }
}
