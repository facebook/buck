/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.sourcepath;

import com.facebook.buck.core.io.ArchiveMemberPath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.google.common.base.Preconditions;
import java.nio.file.Path;

public class SourcePathFactoryForTests {
  public static ArchiveMemberPath toAbsoluteArchiveMemberPath(
      SourcePathResolver sourcePathResolver, SourcePath sourcePath) {
    Preconditions.checkState(sourcePath instanceof ArchiveMemberSourcePath);
    ArchiveMemberSourcePath archiveMemberSourcePath = (ArchiveMemberSourcePath) sourcePath;

    Path archiveAbsolutePath =
        sourcePathResolver.getAbsolutePath(archiveMemberSourcePath.getArchiveSourcePath());

    return ArchiveMemberPath.of(archiveAbsolutePath, archiveMemberSourcePath.getMemberPath());
  }
}
