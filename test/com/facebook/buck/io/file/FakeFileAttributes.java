/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.io.file;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

public class FakeFileAttributes implements BasicFileAttributes {
  private final Object fileKeyObj;
  private final boolean isFileElseDir;
  private final long fileSize;
  private final FileTime timestamp;

  /**
   * @param fileKey something uniquely representing the file this is for
   * @param isFileElseDir this is for a file, iff this is true. Otherwise it's a dir. Never a
   *     symlink or "other" type.
   * @param size file size. Probably only makes sense for files.
   */
  protected FakeFileAttributes(Object fileKey, boolean isFileElseDir, long size) {
    this.fileKeyObj = fileKey;
    this.isFileElseDir = isFileElseDir;
    this.fileSize = size;
    this.timestamp = FileTime.fromMillis(System.currentTimeMillis());
  }

  public static FakeFileAttributes forFileWithSize(Path path, long size) {
    return new FakeFileAttributes(path.toString(), true, size);
  }

  public static FakeFileAttributes forDirectory(Path path) {
    return new FakeFileAttributes(path.toString(), false, 0);
  }

  @Override
  public FileTime creationTime() {
    return this.timestamp;
  }

  @Override
  public Object fileKey() {
    return this.fileKeyObj;
  }

  @Override
  public boolean isDirectory() {
    return !this.isFileElseDir;
  }

  @Override
  public boolean isOther() {
    return false;
  }

  @Override
  public boolean isRegularFile() {
    return this.isFileElseDir;
  }

  @Override
  public boolean isSymbolicLink() {
    return false;
  }

  @Override
  public FileTime lastAccessTime() {
    return this.timestamp;
  }

  @Override
  public FileTime lastModifiedTime() {
    return this.timestamp;
  }

  /**
   * Note: "The size of files that are not regular files is implementation specific and therefore
   * unspecified." Therefore we'll throw unless this is a regular file.
   */
  @Override
  public long size() {
    if (!isRegularFile()) {
      throw new IllegalStateException("not a regular file!");
    }
    return this.fileSize;
  }
}
