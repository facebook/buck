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

package com.facebook.buck.eden;

import com.facebook.buck.io.DefaultProjectFilesystemDelegate;
import com.facebook.buck.io.ProjectFilesystemDelegate;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.eden.thrift.EdenError;
import com.facebook.thrift.TException;
import com.google.common.base.Optional;

import java.io.IOException;
import java.nio.file.Path;

public final class EdenProjectFilesystemDelegate implements ProjectFilesystemDelegate {

  private final EdenMount mount;
  private final DefaultProjectFilesystemDelegate delegate;

  public EdenProjectFilesystemDelegate(EdenMount mount) {
    this.mount = mount;
    this.delegate = new DefaultProjectFilesystemDelegate(mount.getProjectRoot());
  }

  @Override
  public Sha1HashCode computeSha1(Path pathRelativeToProjectRootOrJustAbsolute) throws IOException {
    Path fileToHash = getPathForRelativePath(pathRelativeToProjectRootOrJustAbsolute);

    Optional<Path> entry = mount.getPathRelativeToProjectRoot(fileToHash);
    // TODO(t12516031): Generalize this to check if entry is under any of the Eden client's bind
    // mounts rather than hardcoding a test for buck-out/.
    if (entry.isPresent() && !entry.get().toString().contains("buck-out")) {
      try {
        return mount.getSha1(entry.get());
      } catch (TException | EdenError e) {
        throw new IOException(e);
      }
    }

    return delegate.computeSha1(pathRelativeToProjectRootOrJustAbsolute);
  }

  @Override
  public Path getPathForRelativePath(Path pathRelativeToProjectRootOrJustAbsolute) {
    return delegate.getPathForRelativePath(pathRelativeToProjectRootOrJustAbsolute);
  }
}
