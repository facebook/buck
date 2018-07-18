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

package com.facebook.buck.core.sourcepath;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import java.nio.file.Path;

public final class FakeSourcePath {

  public static PathSourcePath of(String path) {
    return of(new FakeProjectFilesystem(), path);
  }

  public static PathSourcePath of(ProjectFilesystem filesystem, String path) {
    return of(filesystem, filesystem.getPath(path));
  }

  public static PathSourcePath of(Path path) {
    return of(new FakeProjectFilesystem(), path);
  }

  public static PathSourcePath of(ProjectFilesystem filesystem, Path path) {
    return PathSourcePath.of(filesystem, path);
  }
}
