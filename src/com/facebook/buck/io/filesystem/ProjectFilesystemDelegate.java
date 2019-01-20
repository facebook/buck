/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.io.filesystem;

import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Delegate that a {@link ProjectFilesystem} can use to leverage a specialized implementation of
 * certain filesystem operations, tailored to the underlying filesystem. Use of the delegate is
 * often motivated by performance reasons.
 */
public interface ProjectFilesystemDelegate {

  Sha1HashCode computeSha1(Path pathRelativeToProjectRootOrJustAbsolute) throws IOException;

  Path getPathForRelativePath(Path pathRelativeToProjectRoot);

  boolean isExecutable(Path child);

  boolean isSymlink(Path path);

  boolean exists(Path pathRelativeToProjectRoot, LinkOption... options);

  /**
   * @return details about the delegate suitable for writing to a logger. It is recommended that the
   *     keys of this map are unique in namespace of the things a logger may want to log. Values
   *     must be {@link String}, {@code int}, or {@code boolean}.
   */
  ImmutableMap<String, ? extends Object> getDetailsForLogging();
}
