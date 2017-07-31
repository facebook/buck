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

import static com.google.common.collect.Sets.union;

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.versioncontrol.HgCmdLineInterface;
import com.facebook.buck.util.versioncontrol.SparseSummary;
import com.facebook.buck.util.versioncontrol.VersionControlCommandFailedException;
import com.google.common.base.Preconditions;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * AutoSparseState for a mercurial repository.
 *
 * <p>When access to the manifest is requested, load the manifest in chunks around the current path,
 * and store the information in <code>hgManifest</code> (containing {@link ManifestInfo} entries).
 * <code>rawmanifestSeen</code> contains a cache of paths for which we already looked up a subtree
 * to avoid repeatedly loading empty subtrees.
 *
 * <p>When adding files to the sparse profile, the <em>parent directory</em> is added instead, to
 * minimise the sparse profile. An exception is made for paths in the <code>ignore</code> set; for
 * those only the direct file path is added to the profile. This makes it possible to quickly add a
 * series of subdirectories to the profile, but lets us keep some directories (like the project
 * root) out of the sparse profile to prevent extending a sparse profile too far. Only directories
 * which contain files in the manifest (and not just other directories) are explicitly included in
 * the sparse profile.
 *
 * <p>Another optimisation is that once a parent directory has been added to the profile, any child
 * paths are ignored. After all, these are already going to be part of the working copy once the
 * parent directory has been materialised.
 */
public class HgAutoSparseState implements AutoSparseState {
  private static final Logger LOG = Logger.get(AutoSparseState.class);

  private static final String SPARSE_INCLUDE_HEADER = "[include]\n";

  private final Path hgRoot;
  private final HgCmdLineInterface hgCmdLine;
  private final String revisionId;
  private final Set<Path> hgSparseSeen;
  private final Set<Path> buckOutPaths;
  private final Set<String> rawmanifestSeen;

  private Set<Path> ignoredPaths;
  private ManifestTrie hgManifest;
  @Nullable MmappedHgManifest mmappedHgManifest = null;

  private int loadCount = 0;

  public HgAutoSparseState(
      HgCmdLineInterface hgCmdLineInterface,
      Path scRoot,
      String revisionId,
      AutoSparseConfig autoSparseConfig) {
    this.hgRoot = scRoot;
    this.hgCmdLine = hgCmdLineInterface;
    this.revisionId = revisionId;
    this.hgSparseSeen = new HashSet<Path>();
    this.ignoredPaths = autoSparseConfig.ignoredPaths();
    this.buckOutPaths = new HashSet<Path>();
    this.rawmanifestSeen = new HashSet<String>();

    this.hgManifest = new ManifestTrie();
  }

  @Override
  public Path getSCRoot() throws InterruptedException {
    return Preconditions.checkNotNull(hgCmdLine.getHgRoot());
  }

  @Override
  public String getRevisionId() {
    return revisionId;
  }

  // Register a buck-out directory for one of the project filesystems using this state. These are
  // tracked to optimise manifest loading, there is no point is tracking anything under these
  // directories.
  @Override
  public void addBuckOut(Path buckOut) {
    Path relativePath = hgRoot.relativize(buckOut);
    buckOutPaths.add(relativePath);
  }

  @Override
  public void updateConfig(AutoSparseConfig config) {
    ignoredPaths = union(ignoredPaths, config.ignoredPaths());
  }

  @Override
  public SparseSummary materialiseSparseProfile() throws IOException, InterruptedException {
    if (!hgSparseSeen.isEmpty()) {
      LOG.debug("Exporting %d entries to the sparse profile", hgSparseSeen.size());
      LOG.debug("We loaded from the mmap %d times", loadCount);
      try {
        Path exportFile = Files.createTempFile("buck_autosparse_rules", "");
        try (Writer writer = new BufferedWriter(new FileWriter(exportFile.toFile()))) {
          writer.write(SPARSE_INCLUDE_HEADER);
          for (Path path : hgSparseSeen) {
            writer.write(path.toString() + "\n");
          }
        }
        try {
          return hgCmdLine.exportHgSparseRules(exportFile);
        } catch (VersionControlCommandFailedException e) {
          LOG.debug(e, "Sparse profile refresh command failed");
          throw new IOException("Sparse profile refresh command failed", e);
        }
      } catch (IOException e) {
        LOG.debug(e, "Failed to write out sparse profile export");
        throw e;
      }
    } else {
      return SparseSummary.of();
    }
  }

  @Nullable
  @Override
  public ManifestInfo getManifestInfoForFile(Path path) {
    Path relativePath = hgRoot.relativize(path.toAbsolutePath());
    try {
      loadHgManifest(relativePath);
    } catch (VersionControlCommandFailedException | InterruptedException e) {
      LOG.debug("Failed to load the manifest");
      return null;
    }
    return hgManifest.get(relativePath);
  }

  @Override
  public boolean existsInManifest(Path path) {
    Path relativePath = hgRoot.relativize(path);
    try {
      loadHgManifest(relativePath);
    } catch (VersionControlCommandFailedException | InterruptedException e) {
      LOG.debug("Failed to load the manifest");
      return false;
    }
    if (hgManifest.containsDirectory(relativePath)) {
      return true;
    }
    ManifestInfo manifestInfo = getManifestInfoForFile(path);
    return manifestInfo != null;
  }

  @Override
  public void addToSparseProfile(Path path) {
    Path relativePath = hgRoot.relativize(path);
    // check if the path or a parent of it has already been added to the sparse profile
    Path parent = relativePath;
    while (parent != null) {
      if (hgSparseSeen.contains(parent)) {
        return;
      }
      parent = parent.getParent();
    }

    try {
      loadHgManifest(relativePath);
    } catch (VersionControlCommandFailedException | InterruptedException e) {
      LOG.debug("Failed to load the hg manifest");
      return;
    }
    if (hgManifest.isEmpty()) {
      return;
    }

    PathInclusionInfo pathInfo = getPathInfo(relativePath);
    if (pathInfo.isIgnored()) {
      return;
    }
    // Any path not in the manifest, or not a direct parent of a file in the manifest
    // is ignored.
    if (!hgManifest.containsManifest(relativePath) && !hgManifest.containsLeafNodes(relativePath)) {
      if (!hgManifest.contains(relativePath)) {
        LOG.debug("Skipping %s", relativePath);
      } else {
        LOG.debug("Ignoring %s, no directly contained files", relativePath);
      }
      return;
    }
    hgSparseSeen.add(pathInfo.pathToUse);
  }

  private synchronized void loadHgManifest(Path relativePath)
      throws VersionControlCommandFailedException, InterruptedException {
    PathInclusionInfo pathInfo = getPathInfo(relativePath, true);
    if (!hgManifest.contains(relativePath) && !pathInfo.isIgnored()) {
      if (rawmanifestSeen.contains(pathInfo.getPathToUse().toString())) {
        // we asked for this pattern before, no need to ask again.
        return;
      }
      rawmanifestSeen.add(pathInfo.getPathToUse().toString());

      // Obtain hg manifest
      if (mmappedHgManifest == null) {
        Path rawManifestPath = hgCmdLine.extractRawManifest();
        try {
          mmappedHgManifest = new MmappedHgManifest(rawManifestPath);
        } catch (IOException e) {
          throw new VersionControlCommandFailedException(e.getMessage());
        }
      }

      loadCount += 1;
      mmappedHgManifest
          .loadDirectory(pathInfo.getPathToUse().toString())
          .forEach(e -> hgManifest.add(Paths.get(e.getFilename()), ManifestInfo.of(e.getFlag())));

      LOG.debug("Loaded %d manifest entries", hgManifest.size());
    }
  }

  private PathInclusionInfo getPathInfo(Path relativePath) {
    return getPathInfo(relativePath, false);
  }

  private PathInclusionInfo getPathInfo(Path relativePath, boolean loading) {
    final Path finalPath = relativePath;
    if (buckOutPaths.stream().anyMatch(bop -> finalPath.startsWith(bop))) {
      // Buck-out is touched on a lot, but never part of the manifest. Ignore it here
      return PATH_IGNORED;
    }
    // For files, add the direct parent directory instead, unless that's an ignored path
    if (ignoredPaths.contains(relativePath)) {
      // don't add the project directory or explicitly ignored paths directly
      return loading ? new PathInclusionInfo(relativePath) : PATH_IGNORED;
    }
    if (hgManifest.containsManifest(relativePath) || loading) {
      Path directory = relativePath.getParent();
      if (directory != null && !ignoredPaths.contains(directory)) {
        relativePath = directory;
      }
    }
    return new PathInclusionInfo(relativePath);
  }

  private class PathInclusionInfo {
    private Path pathToUse;
    private boolean ignore;

    private PathInclusionInfo(Path pathToUse, boolean ignore) {
      this.pathToUse = pathToUse;
      this.ignore = ignore;
    }

    private PathInclusionInfo(Path pathToUse) {
      this(pathToUse, false);
    }

    Path getPathToUse() {
      return pathToUse;
    }

    boolean isIgnored() {
      return ignore;
    }
  }

  private final PathInclusionInfo PATH_IGNORED = new PathInclusionInfo(Paths.get(""), true);
}
