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

package com.facebook.buck.rules.modern;

import java.nio.file.Path;

/** Provides Buildable's a way to construct Paths to outputs. */
public interface OutputPathResolver {
  /** Returns a relative path to the root directory for intermediate build outputs. */
  Path getTempPath();

  default Path getTempPath(String file) {
    return getTempPath().resolve(file);
  }

  /** Returns a relative path to the output. */
  Path resolvePath(OutputPath outputPath);

  /** Returns a relative path to the root directory for build outputs. */
  Path getRootPath();
}
