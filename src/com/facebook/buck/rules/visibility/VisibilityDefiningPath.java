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

package com.facebook.buck.rules.visibility;

import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/**
 * Object wrapping a cell relative path to a file defining visibility and whether the path is a
 * build file.
 */
@BuckStyleValue
public abstract class VisibilityDefiningPath {
  public abstract ForwardRelativePath getPath();

  public abstract boolean isBuildFile();

  public static VisibilityDefiningPath of(ForwardRelativePath path, boolean isBuildFile) {
    return ImmutableVisibilityDefiningPath.ofImpl(path, isBuildFile);
  }
}
