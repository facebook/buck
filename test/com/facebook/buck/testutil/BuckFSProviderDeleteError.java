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

package com.facebook.buck.testutil;

import com.facebook.buck.core.filesystems.BuckFileSystemProvider;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/*
 * File system created for the purpose of getting delete operations to fail for particular paths
 * in order to exercise code that operates in the presence of such failures, for example when
 * trying to delete a file for which permissions are lacking.  This class could be changed (and renamed)
 * to override additional behavior if further tests require it.
 *
 * Built on top of BuckFS in order to avoid having to implement all the pass-through functions,
 * since BuckFS already does that.  If there is enough overridden behavior it may be better to
 * make this inherit from FileSystem directly rather than going through Buck.  It does not inherit
 * from Jimfs, since the internal behavior there is not exposed enough to easily extend.
 */
public class BuckFSProviderDeleteError extends BuckFileSystemProvider {

  Set<Path> filesNotToDelete;

  public BuckFSProviderDeleteError(FileSystem defaultFileSystem) {
    super(defaultFileSystem);
    filesNotToDelete = new LinkedHashSet<>();
  }

  @Override
  public void delete(Path path) throws IOException {
    if (filesNotToDelete.contains(path)) {
      throw new IOException();
    }
    super.delete(path);
  }

  public void setFileToErrorOnDeletion(Path path) {
    filesNotToDelete.add(path);
  }
}
