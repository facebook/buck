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

package com.facebook.buck.testutil;

import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Assume;

public class AssumePath {

  public static class UnknownPlatformException extends IOException {

    public UnknownPlatformException(Platform platform) {
      super(String.format("Unknown platform: %s", platform));
    }
  }

  public static void assumeNamesAreCaseSensitive(Path pathInFileSystem) {
    try {
      Assume.assumeTrue(
          "File system should be case-sensitive", areNamesCaseSensitive(pathInFileSystem));
    } catch (IOException e) {
      Assume.assumeNoException(e);
    }
  }

  public static void assumeNamesAreCaseInsensitive(Path pathInFileSystem) {
    try {
      Assume.assumeTrue(
          "File system should be case-insensitive", areNamesCaseInsensitive(pathInFileSystem));
    } catch (IOException e) {
      Assume.assumeNoException(e);
    }
  }

  public static boolean areNamesCaseSensitive(Path pathInFileSystem) throws IOException {
    // TODO: Properly detect case sensitivity based on pathInFileSystem.
    // * Linux can mount a case-insensitive file system such as FAT32.
    // * macOS can mount a case-sensitive file system such as case-sensitive HFS+.
    Platform platform = Platform.detect();
    switch (platform) {
      case FREEBSD:
      case LINUX:
        return true;
      case MACOS:
      case WINDOWS:
        return false;
      case UNKNOWN:
      default:
        throw new UnknownPlatformException(platform);
    }
  }

  private static boolean areNamesCaseInsensitive(Path pathInFileSystem) throws IOException {
    return !areNamesCaseSensitive(pathInFileSystem);
  }

  public static void assumeStarIsAllowedInNames(Path pathInFileSystem) {
    try {
      Assume.assumeTrue(
          "File system should allow '*' in names", isStarAllowedInNames(pathInFileSystem));
    } catch (IOException e) {
      Assume.assumeNoException(e);
    }
  }

  public static boolean isStarAllowedInNames(Path pathInFileSystem) throws IOException {
    // TODO: Properly detect valid characters based on pathInFileSystem.
    Platform platform = Platform.detect();
    switch (platform) {
      case FREEBSD:
      case LINUX:
      case MACOS:
        return true;
      case WINDOWS:
        return false;
      case UNKNOWN:
      default:
        throw new UnknownPlatformException(platform);
    }
  }

  public static void assumeQuestionIsAllowedInNames(Path pathInFileSystem) {
    try {
      Assume.assumeTrue(
          "File system should allow '?' in names", isStarAllowedInNames(pathInFileSystem));
    } catch (IOException e) {
      Assume.assumeNoException(e);
    }
  }

  public static boolean isQuestionAllowedInNames(Path pathInFileSystem) throws IOException {
    // TODO: Properly detect valid characters based on pathInFileSystem.
    Platform platform = Platform.detect();
    switch (platform) {
      case FREEBSD:
      case LINUX:
      case MACOS:
        return true;
      case WINDOWS:
        return false;
      case UNKNOWN:
      default:
        throw new UnknownPlatformException(platform);
    }
  }
}
