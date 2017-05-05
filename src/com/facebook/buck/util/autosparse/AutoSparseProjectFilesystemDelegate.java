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

package com.facebook.buck.util.autosparse;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.DefaultProjectFilesystemDelegate;
import com.facebook.buck.io.ProjectFilesystemDelegate;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.util.versioncontrol.SparseSummary;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Virtual project filesystem that answers questions about files via the source control manifest.
 * This removes the need to have all files checked out while Buck parses (a so-called
 * <em>sparse</em> profile.
 *
 * <p>The source control system state is tracked by in an {@link AutoSparseState} instance. Any
 * files queries about files outside the source control system manifest are forwarded to {@link
 * DefaultProjectFilesystemDelegate}.
 */
public final class AutoSparseProjectFilesystemDelegate implements ProjectFilesystemDelegate {

  private static final Logger LOG = Logger.get(AutoSparseProjectFilesystemDelegate.class);

  private final AutoSparseState autoSparseState;
  private final Path scRoot;

  /** Delegate to forward requests to for files that are outside of the hg root. */
  private final ProjectFilesystemDelegate delegate;

  public AutoSparseProjectFilesystemDelegate(AutoSparseState autoSparseState, Path projectRoot)
      throws InterruptedException {
    this.autoSparseState = autoSparseState;
    this.scRoot = autoSparseState.getSCRoot();
    this.delegate = new DefaultProjectFilesystemDelegate(projectRoot);
  }

  @Override
  public void ensureConcreteFilesExist(BuckEventBus eventBus) {
    LOG.debug("Materialising the sparse profile");
    AutoSparseStateEvents.SparseRefreshStarted started =
        new AutoSparseStateEvents.SparseRefreshStarted();
    eventBus.post(started);
    SparseSummary sparseSummary = SparseSummary.of();
    try {
      sparseSummary = autoSparseState.materialiseSparseProfile();
    } catch (IOException | InterruptedException e) {
      Throwable cause = e.getCause();
      String details = cause == null ? e.getMessage() : cause.getMessage();
      AutoSparseStateEvents.SparseRefreshFailed failed =
          new AutoSparseStateEvents.SparseRefreshFailed(started, details);
      eventBus.post(failed);
      throw new HumanReadableException(
          e,
          "Sparse profile could not be materialised. "
              + "Try again or disable the project.enable_autosparse option.");
    } finally {
      eventBus.post(new AutoSparseStateEvents.SparseRefreshFinished(started, sparseSummary));
    }
  }

  @Override
  public Sha1HashCode computeSha1(Path pathRelativeToProjectRootOrJustAbsolute) throws IOException {
    // compute absolute path to ensure it is listed in the sparse profile
    Path path = getPathForRelativePath(pathRelativeToProjectRootOrJustAbsolute);
    return delegate.computeSha1(path);
  }

  @Override
  public Path getPathForRelativePath(Path pathRelativeToProjectRootOrJustAbsolute) {
    Path path = delegate.getPathForRelativePath(pathRelativeToProjectRootOrJustAbsolute);
    if (path.startsWith(scRoot)) {
      includeInSparse(path);
    }
    return path;
  }

  @Override
  public boolean isExecutable(Path child) {
    ManifestInfo manifestentry = autoSparseState.getManifestInfoForFile(child);
    if (manifestentry != null) {
      return manifestentry.isExecutable();
    }
    return delegate.isExecutable(child);
  }

  @Override
  public boolean isSymlink(Path path) {
    path = getPathForRelativePath(path);
    ManifestInfo manifestentry = autoSparseState.getManifestInfoForFile(path);
    if (manifestentry != null) {
      return manifestentry.isLink();
    } else if (autoSparseState.existsInManifest(path)) {
      // it is a directory that exists in the manifest
      return false;
    }
    return delegate.isSymlink(path);
  }

  @Override
  public boolean exists(Path pathRelativeToProjectRoot, LinkOption... options) {
    Path path = getPathForRelativePath(pathRelativeToProjectRoot);
    boolean existsInManifest = autoSparseState.existsInManifest(path);
    return existsInManifest || delegate.exists(path);
  }

  private void includeInSparse(Path path) {
    autoSparseState.addToSparseProfile(path);
  }
}
