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

import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaClassHashesProvider;
import com.facebook.buck.rules.modern.EmptyMemoizerDeserialization;
import com.facebook.buck.util.Memoizer;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Default implementation of {@link JavaClassHashesProvider} interface */
public class DefaultJavaClassHashesProvider implements JavaClassHashesProvider {

  @AddToRuleKey private final SourcePath classNamesToHashesSourcePath;

  @CustomFieldBehavior(EmptyMemoizerDeserialization.class)
  private Memoizer<ImmutableSortedMap<String, HashCode>> classNamesToHashesSupplier;

  public DefaultJavaClassHashesProvider(SourcePath classNamesToHashesSourcePath) {
    this.classNamesToHashesSourcePath = classNamesToHashesSourcePath;
    this.classNamesToHashesSupplier = new Memoizer<>();
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes(
      ProjectFilesystem filesystem, SourcePathResolverAdapter sourcePathResolverAdapter) {
    return classNamesToHashesSupplier.get(
        () -> {
          Path absolutePath =
              sourcePathResolverAdapter.getAbsolutePath(classNamesToHashesSourcePath);
          return readClassNamesToHashes(filesystem, absolutePath);
        });
  }

  private ImmutableSortedMap<String, HashCode> readClassNamesToHashes(
      ProjectFilesystem filesystem, Path classHashesAbsolutePath) {
    List<String> lines;
    try {
      lines = filesystem.readLines(classHashesAbsolutePath);
    } catch (IOException e) {
      throw new BuckUncheckedExecutionException(
          e, "I/O exception during reading from the path: %s", classHashesAbsolutePath);
    }
    return AccumulateClassNamesStep.parseClassHashes(lines);
  }

  @Override
  public void invalidate() {
    this.classNamesToHashesSupplier = new Memoizer<>();
  }
}
