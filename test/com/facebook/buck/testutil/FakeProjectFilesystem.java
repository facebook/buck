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

package com.facebook.buck.testutil;

import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;

import java.io.File;
import java.nio.file.Path;

// TODO(user): Override rest of the methods and provide helper methods to verify its state.
public class FakeProjectFilesystem extends ProjectFilesystem {

  public FakeProjectFilesystem() {
    super(new File("."));
  }

  @Override
  public void rmdir(String path) {
  }

  @Override
  public void mkdirs(Path path) {
  }

  @Override
  public Optional<String> readFileIfItExists(Path path) {
    return Optional.absent();
  }

  @Override
  public void writeContentsToPath(String contents, Path path) {
  }
}
