/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.file.downloader.impl;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.file.downloader.Downloader;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** A {@link Downloader} that pulls content in gradle format from the local file system. */
public class OnDiskGradleDownloader extends AbstractOnDiskDownloader {
  private static final String GRADLE_HOME = "GRADLE_USER_HOME";

  public OnDiskGradleDownloader(Path root) throws FileNotFoundException {
    super(root);
  }

  @Override
  public Path getTargetPath(URI uri) {
    // Get the relative path that the maven item is located at.
    String relativePath = MavenUrlDecoder.toLocalGradlePath(uri);

    Path fullPath = getRoot().resolve(relativePath);

    try {
      // Gradle keeps artifacts in the below format
      // <group>/<artifactId>/<version>/some-hash/<filename>
      // List all the folders in the parent directory, iterate and
      // append hash & filename and return if the file is present.

      if (Files.exists(fullPath.getParent())) {
        try (Stream<Path> files = Files.list(fullPath.getParent())) {
          List<Path> actualPaths =
              files
                  .filter(dir -> Files.isDirectory(dir))
                  .map(dir -> dir.resolve(fullPath.getFileName()))
                  .filter(path -> Files.exists(path))
                  .collect(ImmutableList.toImmutableList());

          // Only return found path if only one artifact matches.
          if (actualPaths.size() == 1) {
            return actualPaths.get(0);
          }
        }
      }
      return fullPath;

    } catch (IOException e) {
      throw new HumanReadableException("Failed to list folders in the directory", e);
    }
  }

  static URL getUrl(String repo, BuckConfig config) throws MalformedURLException {
    String filePath = repo.substring("gradle:".length());

    if (filePath.startsWith(GRADLE_HOME)) {
      String gradleHome = config.getEnvironment().get(GRADLE_HOME);

      if (Strings.isNullOrEmpty(gradleHome)) {
        throw new HumanReadableException(
            "GRADLE_USER_HOME needs to be set for using local gradle cache '%s' See "
                + "https://buckbuild.com/concept/buckconfig.html#maven_repositories "
                + "for how to configure this setting",
            repo);
      }
      filePath = filePath.replace(GRADLE_HOME, gradleHome);
    }

    return new URL("file:" + filePath);
  }
}
