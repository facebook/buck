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
package com.facebook.buck.jvm.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.ArchiveMemberSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Paths;
import java.util.jar.JarFile;

public class JarArchiveDependencySupplier extends ZipArchiveDependencySupplier {
  public JarArchiveDependencySupplier(
      Supplier<ImmutableSortedSet<SourcePath>> jarFiles,
      ProjectFilesystem filesystem) {
    super(jarFiles, filesystem);
  }

  @Override
  public ImmutableSortedSet<SourcePath> getArchiveMembers(SourcePathResolver resolver) {
    return ImmutableSortedSet.copyOf(
        Collections2.filter(
            super.getArchiveMembers(resolver),
            new Predicate<SourcePath>() {
              @Override
              public boolean apply(SourcePath input) {
                ArchiveMemberSourcePath archiveMemberSourcePath = (ArchiveMemberSourcePath) input;

                // Don't include the manifest file, because it contains all the hashes and thus
                // won't have a hash for itself.
                return !archiveMemberSourcePath.getMemberPath().equals(
                    Paths.get(JarFile.MANIFEST_NAME));
              }
            }));
  }
}
