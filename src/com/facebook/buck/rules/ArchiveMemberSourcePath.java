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

package com.facebook.buck.rules;

import com.facebook.buck.model.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;

import java.nio.file.Path;

/**
 * A {@link SourcePath} that can reference a member within an archive.
 */
public final class ArchiveMemberSourcePath extends AbstractSourcePath<ArchiveMemberSourcePath> {
  private final SourcePath archiveSourcePath;
  private final Path memberPath;

  public ArchiveMemberSourcePath(SourcePath archiveSourcePath, Path memberPath) {
    Preconditions.checkState(!memberPath.isAbsolute());

    this.archiveSourcePath =  archiveSourcePath;
    this.memberPath = memberPath;
  }

  public SourcePath getArchiveSourcePath() {
    return archiveSourcePath;
  }

  public Path getMemberPath() {
    return memberPath;
  }

  @Override
  protected Object asReference() {
    AbstractSourcePath<?> archiveSourcePath = (AbstractSourcePath<?>) this.archiveSourcePath;

    return new Pair<>(archiveSourcePath.asReference(), memberPath);
  }

  @Override
  protected int compareReferences(ArchiveMemberSourcePath o) {
    if (o == this) {
      return 0;
    }

    return ComparisonChain.start()
        .compare(archiveSourcePath, o.archiveSourcePath)
        .compare(memberPath, o.memberPath)
        .result();
  }
}
