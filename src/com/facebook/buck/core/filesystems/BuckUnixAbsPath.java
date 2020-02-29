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

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * {@link com.facebook.buck.core.filesystems.BuckUnixPath} with {@link
 * com.facebook.buck.core.filesystems.AbsPath} marker.
 */
class BuckUnixAbsPath extends BuckUnixPath implements AbsPath {
  protected BuckUnixAbsPath(BuckFileSystem fs, String[] segments) {
    super(fs, segments);
  }

  @Override
  public BuckUnixAbsPath normalize() {
    return (BuckUnixAbsPath) super.normalize();
  }

  @Override
  public BuckUnixAbsPath toRealPath(LinkOption... options) throws IOException {
    return (BuckUnixAbsPath) super.toRealPath(options);
  }

  @Override
  public BuckUnixAbsPath resolve(Path obj) {
    return (BuckUnixAbsPath) super.resolve(obj);
  }

  @Override
  public BuckUnixAbsPath resolve(String other) {
    return (BuckUnixAbsPath) super.resolve(other);
  }

  @Override
  public BuckUnixAbsPath getParent() {
    return (BuckUnixAbsPath) super.getParent();
  }

  @Override
  public BuckUnixAbsPath getRoot() {
    return (BuckUnixAbsPath) super.getRoot();
  }

  @Override
  public BuckUnixRelPath relativize(Path obj) {
    return (BuckUnixRelPath) super.relativize(obj);
  }
}
