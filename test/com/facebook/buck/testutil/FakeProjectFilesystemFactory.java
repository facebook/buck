/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import com.facebook.buck.io.filesystem.EmbeddedCellBuckOutInfo;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.util.config.Config;
import java.nio.file.Path;
import java.util.Optional;

public class FakeProjectFilesystemFactory implements ProjectFilesystemFactory {

  @Override
  public ProjectFilesystem createProjectFilesystem(
      Path root, Config config, Optional<EmbeddedCellBuckOutInfo> embeddedCellBuckOutInfo) {
    return new FakeProjectFilesystem(root);
  }

  @Override
  public ProjectFilesystem createProjectFilesystem(Path root, Config config) {
    return new FakeProjectFilesystem(root);
  }

  @Override
  public ProjectFilesystem createProjectFilesystem(Path root) {
    return new FakeProjectFilesystem(root);
  }

  @Override
  public ProjectFilesystem createOrThrow(Path path) {
    return new FakeProjectFilesystem(path);
  }
}
