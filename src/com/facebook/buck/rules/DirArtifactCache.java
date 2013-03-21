/*
 * Copyright 2012-present Facebook, Inc.
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

import com.google.common.base.Preconditions;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

public class DirArtifactCache implements ArtifactCache {
  private final static Logger logger = Logger.getLogger(DirArtifactCache.class.getName());

  private final File cacheDir;

  public DirArtifactCache(File cacheDir) throws IOException {
    this.cacheDir = Preconditions.checkNotNull(cacheDir);
    Files.createParentDirs(cacheDir);
    if (!cacheDir.mkdir() && !cacheDir.exists()) {
      throw new IOException(String.format("Failed to create cache directory: \"%s\"",
          cacheDir.getPath()));
    }
  }

  public boolean fetch(RuleKey ruleKey, File output) {
    if (!ruleKey.isIdempotent()) {
      return false;
    }
    boolean success = false;
    File cacheEntry = new File(cacheDir, ruleKey.toString());
    if (cacheEntry.exists()) {
      try {
        Files.createParentDirs(output);
        Files.copy(cacheEntry, output);
        success = true;
      } catch (IOException e) {
        logger.warning(String.format("Artifact fetch(%s, %s) error: %s",
            ruleKey,
            output.getPath(),
            e.getMessage()));
      }
    }
    logger.info(String.format("Artifact fetch(%s, %s) cache %s",
        ruleKey,
        output.getPath(),
        (success ? "hit" : "miss")));
    return success;
  }

  public void store(RuleKey ruleKey, File output) {
    if (!ruleKey.isIdempotent()) {
      return;
    }
    File cacheEntry = new File(cacheDir, ruleKey.toString());
    try {
      // Write to a temporary file and move the file to its final location atomically to protect
      // against partial artifacts (whether due to buck interruption or filesystem failure) posing
      // as valid artifacts during subsequent buck runs.
      File tmpCacheEntry = File.createTempFile(ruleKey.toString(), ".tmp", cacheDir);
      Files.copy(output, tmpCacheEntry);
      Files.move(tmpCacheEntry, cacheEntry);
    } catch (IOException e) {
      logger.warning(String.format("Artifact store(%s, %s) error: %s",
          ruleKey,
          output.getPath(),
          e.getMessage()));
    }
  }
}
