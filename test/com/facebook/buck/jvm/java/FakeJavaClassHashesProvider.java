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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaClassHashesProvider;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;

public class FakeJavaClassHashesProvider implements JavaClassHashesProvider {

  private final ImmutableSortedMap<String, HashCode> classNamesToHashes;

  public FakeJavaClassHashesProvider(ImmutableSortedMap<String, HashCode> classNamesToHashes) {
    this.classNamesToHashes = classNamesToHashes;
  }

  public FakeJavaClassHashesProvider() {
    this(ImmutableSortedMap.of());
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes(
      ProjectFilesystem filesystem, SourcePathResolverAdapter sourcePathResolverAdapter) {
    return classNamesToHashes;
  }

  @Override
  public void invalidate() {}
}
