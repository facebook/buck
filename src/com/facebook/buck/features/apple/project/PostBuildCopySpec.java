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

package com.facebook.buck.features.apple.project;

import com.facebook.buck.core.filesystems.RelPath;
import java.nio.file.Path;

/**
 * What should be copied after all the dependencies are built and where. Used to prepare auxiliary
 * files if there is some constraint for their paths (e.g. localized resources should reside inside
 * a *.lproj directory).
 */
public class PostBuildCopySpec {

  private final Path from;
  private final Path to;

  public PostBuildCopySpec(Path from, Path to) {
    this.from = from;
    this.to = to;
  }

  public Path getFrom() {
    return from;
  }

  public Path getTo() {
    return to;
  }

  PostBuildCopySpec resolveAgainst(RelPath other) {
    return new PostBuildCopySpec(other.resolve(from), other.resolve(to));
  }
}
