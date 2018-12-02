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

import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.eden.thrift.EdenError;
import com.facebook.eden.thrift.SHA1Result;
import com.facebook.thrift.TException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * Utility to make requests to the Eden thrift API for an (Eden mount point, Buck project root)
 * pair. The Buck project root must be contained by the Eden mount point.
 */
public class EdenMount {
  private final EdenClientPool pool;

  /** Value of the mountPoint argument to use when communicating with Eden via the Thrift API. */
  private final String mountPoint;

  /** Root of the Buck project of interest that is contained by this {@link EdenMount}. */
  private final Path projectRoot;

  /**
   * Relative path used to resolve paths under the {@link #projectRoot} in the context of the {@link
   * #mountPoint}.
   */
  private final Path prefix;

  /**
   * Creates a new object for communicating with Eden that is bound to the specified (Eden mount
   * point, Buck project root) pair. It must be the case that {@code
   * projectRoot.startsWith(mountPoint)}.
   */
  EdenMount(EdenClientPool pool, Path mountPoint, Path projectRoot) {
    Preconditions.checkArgument(
        projectRoot.startsWith(mountPoint),
        "Eden mount point %s must contain the Buck project at %s.",
        mountPoint,
        projectRoot);
    this.pool = pool;
    this.mountPoint = mountPoint.toString();
    this.projectRoot = projectRoot;
    this.prefix = mountPoint.relativize(projectRoot);
  }

  /** @return an Eden mount point if {@code projectRoot} is backed by Eden or {@code null}. */
  public static Optional<EdenMount> createEdenMountForProjectRoot(
      Path projectRoot, EdenClientPool pool) {
    Path rootSymlink = projectRoot.resolve(".eden/root");
    Path rootOfEdenMount;
    try {
      rootOfEdenMount = Files.readSymbolicLink(rootSymlink);
    } catch (IOException e) {
      return Optional.empty();
    }

    return Optional.of(new EdenMount(pool, rootOfEdenMount, projectRoot));
  }

  /** @return The root to the Buck project that this {@link EdenMount} represents. */
  public Path getProjectRoot() {
    return projectRoot;
  }

  @VisibleForTesting
  Path getPrefix() {
    return prefix;
  }

  /** @param entry is a path that is relative to {@link #getProjectRoot()}. */
  public Sha1HashCode getSha1(Path entry) throws EdenError, IOException, TException {
    List<SHA1Result> results =
        pool.getClient().getSHA1(mountPoint, ImmutableList.of(normalizePathArg(entry)));
    SHA1Result result = Iterables.getOnlyElement(results);
    if (result.getSetField() == SHA1Result.SHA1) {
      return Sha1HashCode.fromBytes(result.getSha1());
    } else {
      throw result.getError();
    }
  }

  public ImmutableList<Path> getBindMounts() {
    List<String> bindMounts;
    try {
      bindMounts = pool.getClient().getBindMounts(mountPoint);
    } catch (IOException | TException e) {
      throw new RuntimeException(e);
    }

    return bindMounts.stream().map(Paths::get).collect(ImmutableList.toImmutableList());
  }

  /**
   * Returns the path relative to {@link #getProjectRoot()} if {@code path} is contained by {@link
   * #getProjectRoot()}; otherwise, returns {@link Optional#empty()}.
   */
  Optional<Path> getPathRelativeToProjectRoot(Path path) {
    if (path.isAbsolute()) {
      if (path.startsWith(projectRoot)) {
        return Optional.of(projectRoot.relativize(path));
      } else {
        return Optional.empty();
      }
    } else {
      return Optional.of(path);
    }
  }

  /**
   * @param entry is a path that is relative to {@link #getProjectRoot()}.
   * @return a path that is relative to {@link #mountPoint}.
   */
  private String normalizePathArg(Path entry) {
    return prefix.resolve(entry).toString();
  }

  @Override
  public String toString() {
    return String.format("EdenMount{mountPoint=%s, prefix=%s}", mountPoint, prefix);
  }
}
