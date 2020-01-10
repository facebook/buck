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

import java.nio.file.Path;

/** Metadata for Xcode to lay out and reference an Xcconfig file. */
public class XcconfigBaseConfiguration {
  private final String name;
  private final Path path;

  public XcconfigBaseConfiguration(String name, Path path) {
    this.name = name;
    this.path = path;
  }

  public String getName() {
    return name;
  }

  public Path getPath() {
    return path;
  }
}
