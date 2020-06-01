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

package com.facebook.buck.features.python;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.io.file.MorePaths;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@BuckStyleValueWithBuilder
public abstract class PythonResolvedComponentsGroup {

  @AddToRuleKey
  abstract ImmutableMultimap<BuildTarget, PythonComponents.Resolved> getComponents();

  private static HumanReadableException createDuplicateError(
      String type, Path destination, BuildTarget sourceA, BuildTarget sourceB) {
    return new HumanReadableException(
        "found duplicate entries for %s %s when creating python package (%s and %s)",
        type, destination, sourceA, sourceB);
  }

  // Return whether two files are identical.
  private static boolean areFilesTheSame(Path a, Path b) throws IOException {
    if (a.equals(b)) {
      return true;
    }

    final long totalSize = Files.size(a);
    if (totalSize != Files.size(b)) {
      return false;
    }

    try (InputStream ia = Files.newInputStream(a);
        InputStream ib = Files.newInputStream(b)) {
      final int bufSize = 8192;
      final byte[] aBuf = new byte[bufSize];
      final byte[] bBuf = new byte[bufSize];
      for (int toRead = (int) totalSize; toRead > 0; ) {
        final int chunkSize = Integer.min(toRead, bufSize);
        ByteStreams.readFully(ia, aBuf, 0, chunkSize);
        ByteStreams.readFully(ib, bBuf, 0, chunkSize);
        for (int idx = 0; idx < chunkSize; idx++) {
          if (aBuf[idx] != bBuf[idx]) {
            return false;
          }
        }
        toRead -= chunkSize;
      }
    }

    return true;
  }

  // Helper to walk sets of components contributed from different rules and merge them, throwin an
  // error on duplicates.
  public void forEachComponent(String type, PythonComponents.Resolved.ComponentConsumer consumer)
      throws IOException {
    Map<Path, Path> seen = new HashMap<>();
    Map<Path, BuildTarget> sources = new HashMap<>();
    for (Map.Entry<BuildTarget, PythonComponents.Resolved> entry : getComponents().entries()) {
      entry
          .getValue()
          .forEachPythonComponent(
              (destination, source) -> {
                Path existing = seen.put(destination, source);
                if (existing == null) {
                  sources.put(destination, entry.getKey());
                  consumer.accept(destination, source);
                } else if (!areFilesTheSame(existing, source)) {
                  throw createDuplicateError(
                      type,
                      destination,
                      entry.getKey(),
                      Objects.requireNonNull(sources.get(destination)));
                }
              });
    }
  }

  /** Run {@code consumer} on all modules, throwing an error on duplicates. */
  public void forEachModule(
      Optional<Path> defaultInitPy, PythonComponents.Resolved.ComponentConsumer consumer)
      throws IOException {
    Set<Path> packages = new HashSet<>();
    Set<Path> packagesWithInit = new HashSet<>();

    forEachComponent(
        "module",
        (destination, source) -> {

          // Record all packages that do and don't contain an `__init__.py`.
          String ext = MorePaths.getFileExtension(destination);
          if (defaultInitPy.isPresent()
                  // TODO(agallagher): This shouldn't be necessary, but currently, prebuilt module
                  // dirs can include files that aren't really modules.
                  // TODO(agallagher): Why do we need to handle `.pyi` types too?
                  && (PythonUtil.isModuleExt(ext))
              || ext.equals("pyi")) {
            // Record all "packages" we see as we go.
            for (Path pkg = destination.getParent();
                pkg != null && !packages.contains(pkg);
                pkg = pkg.getParent()) {
              packages.add(pkg);
            }
            // Record all existing `__init__.py` files.
            if (destination.getFileName().toString().equals(PythonUtil.INIT_PY)) {
              packagesWithInit.add(destination.getParent());
            }
          }

          consumer.accept(destination, source);
        });

    // If a default `__init__.py` is provided, use for all packages w/o one.
    if (defaultInitPy.isPresent()) {
      for (Path pkg : Sets.difference(packages, packagesWithInit)) {
        consumer.accept(pkg.resolve(PythonUtil.INIT_PY), defaultInitPy.get());
      }
    }
  }
}
