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

package com.facebook.buck.io;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Common functions that are done with a {@link ProjectFilesystem}. */
public class MoreProjectFilesystems {
  /** Utility class: do not instantiate. */
  private MoreProjectFilesystems() {}

  /**
   * Creates a symlink at {@code
   * projectFilesystem.getRootPath().resolve(pathToDesiredLinkUnderProjectRoot)} that points to
   * {@code projectFilesystem.getRootPath().resolve(pathToExistingFileUnderProjectRoot)} using a
   * relative symlink.
   *
   * @param pathToDesiredLinkUnderProjectRoot must reference a file, not a directory.
   * @param pathToExistingFileUnderProjectRoot must reference a file, not a directory.
   * @param projectFilesystem the projectFileSystem.
   * @return Path to the newly created symlink (relative path on Unix absolute path on Windows).
   */
  public static Path createRelativeSymlink(
      Path pathToDesiredLinkUnderProjectRoot,
      Path pathToExistingFileUnderProjectRoot,
      ProjectFilesystem projectFilesystem)
      throws IOException {

    Path target =
        MorePaths.getRelativePath(
            pathToExistingFileUnderProjectRoot, pathToDesiredLinkUnderProjectRoot.getParent());

    projectFilesystem.createSymLink(
        projectFilesystem.getRootPath().resolve(pathToDesiredLinkUnderProjectRoot), target, false);

    return target;
  }

  public static boolean fileContentsDiffer(
      InputStream contents, Path path, ProjectFilesystem projectFilesystem) throws IOException {
    try {
      // Hash the contents of the file at path so we don't have to pull the whole thing into memory.
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      byte[] pathDigest;
      try (InputStream is = projectFilesystem.newFileInputStream(path)) {
        pathDigest = inputStreamDigest(is, sha1);
      }
      // Hash 'contents' and see if the two differ.
      sha1.reset();
      byte[] contentsDigest = inputStreamDigest(contents, sha1);
      return !Arrays.equals(pathDigest, contentsDigest);
    } catch (NoSuchFileException e) {
      // If the file doesn't exist, we need to create it.
      return true;
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  private static byte[] inputStreamDigest(InputStream inputStream, MessageDigest messageDigest)
      throws IOException {
    try (DigestInputStream dis = new DigestInputStream(inputStream, messageDigest)) {
      byte[] buf = new byte[4096];
      while (true) {
        // Read the contents of the existing file so we can hash it.
        if (dis.read(buf) == -1) {
          break;
        }
      }
      return dis.getMessageDigest().digest();
    }
  }
}
