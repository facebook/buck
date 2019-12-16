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
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * Contains Python components (e.g. modules, resources) to be used by an executing Python binary
 * step (e.g. {@link PexStep}).
 */
@Value.Immutable(copy = true)
@BuckStyleImmutable
abstract class AbstractPythonResolvedPackageComponents {

  protected abstract ImmutableMultimap<BuildTarget, PythonComponents.Resolved> getModules();

  protected abstract ImmutableMultimap<BuildTarget, PythonComponents.Resolved> getResources();

  protected abstract ImmutableMultimap<BuildTarget, PythonComponents.Resolved> getModuleDirs();

  protected abstract ImmutableMultimap<BuildTarget, PythonComponents.Resolved> getNativeLibraries();

  protected abstract Optional<Boolean> isZipSafe();

  private HumanReadableException createDuplicateError(
      String type, Path destination, BuildTarget sourceA, BuildTarget sourceB) {
    return new HumanReadableException(
        "found duplicate entries for %s %s when creating python package (%s and %s)",
        type, destination, sourceA, sourceB);
  }

  // Return whether two files are identical.
  private boolean areFilesTheSame(Path a, Path b) throws IOException {
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
  private void forEachComponent(
      String type,
      Iterable<Map.Entry<BuildTarget, PythonComponents.Resolved>> components,
      PythonComponents.Resolved.ComponentConsumer consumer)
      throws IOException {
    Map<Path, Path> seen = new HashMap<>();
    Map<Path, BuildTarget> sources = new HashMap<>();
    for (Map.Entry<BuildTarget, PythonComponents.Resolved> entry : components) {
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
  public void forEachModule(PythonComponents.Resolved.ComponentConsumer consumer)
      throws IOException {
    forEachComponent(
        "module", Iterables.concat(getModules().entries(), getModuleDirs().entries()), consumer);
  }

  /** Run {@code consumer} on all resources, throwing an error on duplicates. */
  public void forEachResource(PythonComponents.Resolved.ComponentConsumer consumer)
      throws IOException {
    forEachComponent("resource", getResources().entries(), consumer);
  }

  /** Run {@code consumer} on all native libraries, throwing an error on duplicates. */
  public void forEachNativeLibrary(PythonComponents.Resolved.ComponentConsumer consumer)
      throws IOException {
    forEachComponent("native library", getNativeLibraries().entries(), consumer);
  }

  @VisibleForTesting
  ImmutableMap<Path, Path> getAllModules() throws IOException {
    ImmutableMap.Builder<Path, Path> builder = ImmutableMap.builder();
    forEachModule(builder::put);
    return builder.build();
  }

  @VisibleForTesting
  ImmutableMap<Path, Path> getAllResources() throws IOException {
    ImmutableMap.Builder<Path, Path> builder = ImmutableMap.builder();
    forEachResource(builder::put);
    return builder.build();
  }

  @VisibleForTesting
  ImmutableMap<Path, Path> getAllNativeLibraries() throws IOException {
    ImmutableMap.Builder<Path, Path> builder = ImmutableMap.builder();
    forEachNativeLibrary(builder::put);
    return builder.build();
  }
}
