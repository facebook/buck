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

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.versioncontrol.HgCmdLineInterface;
import com.facebook.buck.util.versioncontrol.SparseSummary;
import com.facebook.buck.util.versioncontrol.VersionControlCommandFailedException;
import com.google.common.base.Preconditions;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
 * <p>The first time access to the manifest is requested, load the manifest in one big blob and
 * store the information in <code>hgManifest</code> (containing {@link ManifestInfo} entries) and
 * <code>hgKnownDirectories</code> (a set of directories containing files with manifest entries).
 *
 * <p>When adding files to the sparse profile, the <em>parent directory</em> is added instead, to
 * minimise the sparse profile. An exception is made for paths in the <code>ignore</code> set; for
 * those only the direct file path is added to the profile. This makes it possible to quickly add a
 * series of subdirectories to the profile, but lets us keep some directories (like the project
 * root) out of the sparse profile to prevent extending a sparse profile too far.
 *
 * <p>Another optimisation is that once a parent directory has been adedd to the profile, any child
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
  private final Set<Path> ignoredPaths;

  private ManifestTrie hgManifest;
  private boolean hgManifestLoaded;

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

    this.hgManifest = new ManifestTrie();
    this.hgManifestLoaded = false;
  }

  @Override
  public Path getSCRoot() throws InterruptedException {
    return Preconditions.checkNotNull(hgCmdLine.getHgRoot());
  }

  @Override
  public String getRevisionId() {
    return revisionId;
  }

  @Override
  public SparseSummary materialiseSparseProfile() throws IOException, InterruptedException {
    if (!hgSparseSeen.isEmpty()) {
      LOG.debug("Exporting %d entries to the sparse profile", hgSparseSeen.size());
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
    try {
      loadHgManifest();
    } catch (VersionControlCommandFailedException | InterruptedException e) {
      LOG.debug("Failed to load the manifest");
      return null;
    }
    Path relativePath = hgRoot.relativize(path.toAbsolutePath());
    return hgManifest.get(relativePath);
  }

  @Override
  public boolean existsInManifest(Path path) {
    try {
      loadHgManifest();
    } catch (VersionControlCommandFailedException | InterruptedException e) {
      LOG.debug("Failed to load the manifest");
      return false;
    }
    Path relativePath = hgRoot.relativize(path);
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
    // Obtain hg manifest
    try {
      loadHgManifest();
    } catch (VersionControlCommandFailedException | InterruptedException e) {
      LOG.debug("Failed to load the hg manifest");
      return;
    }
    if (hgManifest.isEmpty()) {
      return;
    }
    // Any path not in the manifest, or not a direct parent of a file in the manifest
    // is ignored.
    if (!hgManifest.containsManifest(relativePath) && !hgManifest.containsLeafNodes(relativePath)) {
      LOG.debug("Not adding unknown file or directory %s to sparse profile", relativePath);
      return;
    }
    // For files, add the direct parent directory instead, unless that's an ignored path
    if (ignoredPaths.contains(relativePath)) {
      // don't add the project directory directly
      return;
    }
    if (hgManifest.containsManifest(relativePath)) {
      Path directory = relativePath.getParent();
      if (directory != null && !ignoredPaths.contains(directory)) {
        relativePath = directory;
      }
    }

    hgSparseSeen.add(relativePath);
  }

  private void loadHgManifest() throws VersionControlCommandFailedException, InterruptedException {
    if (!hgManifestLoaded) {
      // Cache manifest data
      try (InputStream is = new FileInputStream(hgCmdLine.extractRawManifest());
          BufferedReader reader =
              new BufferedReader(
                  new InputStreamReader(
                      is,
                      // Here is to hoping this is the correct codec on all platforms; Mercurial
                      // doesn't care what the filesystem encoding is and just stores the raw bytes.
                      System.getProperty("file.encoding", "UTF-8"))); ) {

        String line;
        while ((line = reader.readLine()) != null) {
          String parts[] = line.split("\0", 2);
          if (parts.length != 2) {
            // not a valid raw manifest line, skip
            continue;
          }
          Path path = Paths.get(parts[0]);
          // We ignore the hash portion of the manifest entry, no need to bloat up memory with
          // data we don't use.
          String flag = "";
          try {
            flag = parts[1].substring(40);
          } catch (IndexOutOfBoundsException e) {
            // not a valid raw manifest line, skip
            continue;
          }
          if (flag.equals("d")) {
            // deletion entry, remove existing manifest entry from the map
            hgManifest.remove(path);
            continue;
          }

          hgManifest.add(path, ManifestInfo.of(flag));
        }

      } catch (IOException e) {
        throw new VersionControlCommandFailedException(
            "Unable to load raw manifest into an inputstream");
      }

      hgManifestLoaded = true;
      LOG.debug("Loaded %d manifest entries", hgManifest.size());
    }
  }
}
