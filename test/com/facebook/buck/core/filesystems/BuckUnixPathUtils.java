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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;

public class BuckUnixPathUtils {

  /** Creates a {@link BuckUnixPath}. */
  public static BuckUnixPath createPath(String pathString) {
    BuckFileSystemProvider provider =
        new BuckFileSystemProvider(FileSystems.getDefault().provider());
    Path path = provider.getFileSystem(URI.create("file:///")).getPath(pathString);
    assertTrue(path instanceof BuckUnixPath);
    return (BuckUnixPath) path;
  }

  public static Path createJavaPath(String pathString) {
    FileSystemProvider provider = FileSystems.getDefault().provider();
    assertFalse(provider instanceof BuckFileSystemProvider);
    Path path = provider.getFileSystem(URI.create("file:///")).getPath(pathString);
    assertFalse(path instanceof BuckUnixPath);
    return path;
  }
}
