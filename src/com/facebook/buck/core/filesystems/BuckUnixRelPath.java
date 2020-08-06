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

package com.facebook.buck.core.filesystems;

/**
 * {@link com.facebook.buck.core.filesystems.BuckUnixPath} with {@link
 * com.facebook.buck.core.filesystems.RelPath} marker.
 */
class BuckUnixRelPath extends BuckUnixPath implements RelPath {
  protected BuckUnixRelPath(BuckFileSystem fs, String[] segments) {
    super(fs, segments);
  }

  @Override
  public BuckUnixRelPath normalize() {
    return (BuckUnixRelPath) super.normalize();
  }

  @Override
  public BuckUnixRelPath getParent() {
    return (BuckUnixRelPath) super.getParent();
  }

  @Override
  public BuckUnixRelPath subpath(int beginIndex, int endIndex) {
    return (BuckUnixRelPath) super.subpath(beginIndex, endIndex);
  }
}
