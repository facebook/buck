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

package com.facebook.buck.core.path;

import com.facebook.buck.core.filesystems.BuckFileSystem;
import com.facebook.buck.core.filesystems.BuckUnixPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

/**
 * A normalized relative path object which:
 *
 * <ul>
 *   <li>Does not contain dot or dot-dot
 *   <li>Does not start and does not end with slash
 *   <li>Does not contain slash-slash
 * </ul>
 */
public class ForwardRelativePath implements Comparable<ForwardRelativePath> {
  public static final ForwardRelativePath EMPTY = new ForwardRelativePath(new String[0]);

  private final String[] segments;

  private ForwardRelativePath(String[] segments) {
    this.segments = segments;
  }

  /**
   * Parse a string into path.
   *
   * <p>This function throws if path is not normalized (e. g. contains two consecutive slashes).
   */
  public static ForwardRelativePath of(String path) {
    return ofSubstring(path, 0);
  }

  /**
   * Parse a string into path removing .
   *
   * <p>This function throws if path is not normalized (e. g. contains two consecutive slashes).
   *
   * @param offset is the number of characters to be removed from the path before parsing (useful in
   *     certain cases to avoid extra string allocation).
   */
  public static ForwardRelativePath ofSubstring(String path, int offset) {
    Preconditions.checkArgument(offset <= path.length());
    if (offset == path.length()) {
      return EMPTY;
    }

    return new ForwardRelativePath(splitAndIntern(path, offset));
  }

  private static class Substring {
    private final String string;
    private final int offset;

    public Substring(String string, int offset) {
      Preconditions.checkArgument(offset <= string.length());
      this.string = string;
      this.offset = offset;
    }

    @Override
    public String toString() {
      return string.substring(offset);
    }
  }

  private static String[] splitAndIntern(String path, int offset) {
    Preconditions.checkState(path.length() > offset);

    Substring pathSubstring = new Substring(path, offset);

    Preconditions.checkArgument(
        !path.startsWith("/", offset), "path must not start with slash: %s", pathSubstring);
    Preconditions.checkArgument(
        !path.endsWith("/"), "path must not end with slash: %s", pathSubstring);

    ArrayList<String> segments = new ArrayList<>();

    int offsetAfterLastSlash = offset;
    for (int i = offset; i != path.length() + 1; ++i) {
      char c = i != path.length() ? path.charAt(i) : '/';
      Preconditions.checkArgument(c != '\\', "backslash in path: %s", pathSubstring);
      if (c == '/') {
        if (i - offsetAfterLastSlash == 0) {
          throw new IllegalArgumentException("two slashes in path: " + pathSubstring);
        }
        if (i - offsetAfterLastSlash == 1) {
          Preconditions.checkArgument(path.charAt(i - 1) != '.', "dot in path: %s", pathSubstring);
        }
        if (i - offsetAfterLastSlash == 2) {
          Preconditions.checkArgument(
              path.charAt(i - 1) != '.' || path.charAt(i - 2) != '.',
              "dot-dot in path: %s",
              pathSubstring);
        }
        String segment = path.substring(offsetAfterLastSlash, i);
        segments.add(segment.intern());
        offsetAfterLastSlash = i + 1;
      }
    }

    return segments.toArray(new String[0]);
  }

  /**
   * Construct from given relative {@link Path}.
   *
   * <p>This functions calls {@link Path#normalize()}. Throw if path is not relative.
   */
  public static ForwardRelativePath ofPath(Path path) {
    Preconditions.checkArgument(!path.isAbsolute(), "path must not be absolute: %s", path);
    path = path.normalize();
    if (path instanceof BuckUnixPath) {
      String[] segments = ((BuckUnixPath) path).getSegmentsUnsafe();
      if (segments.length == 0) {
        return EMPTY;
      }
      for (String segment : segments) {
        Preconditions.checkArgument(!segment.equals("."), "dot in path: %s", path);
        Preconditions.checkArgument(!segment.equals(".."), "dot-dot in path: %s", path);
      }
      return new ForwardRelativePath(segments);
    } else {
      String pathString = path.toString();
      if (Platform.detect() == Platform.WINDOWS) {
        // Suboptimal
        pathString = pathString.replace('\\', '/');
      }
      return of(pathString);
    }
  }

  /** Append given path to the current path */
  public ForwardRelativePath resolve(ForwardRelativePath other) {
    if (this.isEmpty()) {
      return other;
    } else if (other.isEmpty()) {
      return this;
    } else {
      // skip validation
      String[] segments = new String[this.segments.length + other.segments.length];
      System.arraycopy(this.segments, 0, segments, 0, this.segments.length);
      System.arraycopy(other.segments, 0, segments, this.segments.length, other.segments.length);
      return new ForwardRelativePath(segments);
    }
  }

  public ForwardRelativePath resolve(String other) {
    return resolve(of(other));
  }

  public boolean isEmpty() {
    return segments.length == 0;
  }

  /**
   * Convert file path to a relative path in given filesystem.
   *
   * <p>Note this function is optimized for {@link BuckFileSystem} (avoids re-parsing and
   * re-interning).
   */
  public Path toPath(FileSystem fileSystem) {
    if (fileSystem instanceof BuckFileSystem) {
      return ((BuckFileSystem) fileSystem).getPathFromSegmentsUnsafe(segments);
    } else {
      return fileSystem.getPath(this.toString());
    }
  }

  /** Convert this path to the {@link Path} of {@link FileSystems#getDefault()}. */
  public Path toPathDefaultFileSystem() {
    return toPath(FileSystems.getDefault());
  }

  public RelPath toRelPath(FileSystem fileSystem) {
    return RelPath.of(toPath(fileSystem));
  }

  @Override
  public String toString() {
    if (segments.length == 0) {
      return "";
    } else if (segments.length == 1) {
      return segments[0];
    } else {
      return String.join("/", segments);
    }
  }

  /** Last segment of path */
  public Optional<ForwardRelativePath> nameAsPath() {
    if (isEmpty()) {
      return Optional.empty();
    } else {
      return Optional.of(new ForwardRelativePath(new String[] {segments[segments.length - 1]}));
    }
  }

  /** Path without last segment */
  public Optional<ForwardRelativePath> parent() {
    if (isEmpty()) {
      return Optional.empty();
    } else if (segments.length == 1) {
      return Optional.of(EMPTY);
    } else {
      return Optional.of(new ForwardRelativePath(Arrays.copyOf(segments, segments.length - 1)));
    }
  }

  /** A string to be prepended to another path to make a relative path */
  public String toPathPrefix() {
    if (isEmpty()) {
      return "";
    } else {
      return toString() + "/";
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ForwardRelativePath that = (ForwardRelativePath) o;
    return Arrays.equals(segments, that.segments);
  }

  /**
   * This path starts with given path.
   *
   * <p>{@code ab/cd} starts with {@code ab/cd}, {@code ab}, but not {@code ab/c}.
   */
  public boolean startsWith(ForwardRelativePath path) {
    if (this.segments.length < path.segments.length) {
      return false;
    }

    for (int i = 0; i != path.segments.length; ++i) {
      if (!this.segments[i].equals(path.segments[i])) {
        return false;
      }
    }

    return true;
  }

  /**
   * This path ends with given path.
   *
   * <p>{@code ab/cd} ends with {@code ab/cd}, {@code cd}, but not {@code b/cd}.
   */
  public boolean endsWith(ForwardRelativePath path) {
    if (this.segments.length < path.segments.length) {
      return false;
    }

    for (int i = 0; i != path.segments.length; ++i) {
      if (!this.segments[this.segments.length - path.segments.length + i].equals(
          path.segments[i])) {
        return false;
      }
    }

    return true;
  }

  public ImmutableList<String> segments() {
    return ImmutableList.copyOf(segments);
  }

  /**
   * Constructs a relative path between this path and a given path.
   *
   * <p>Returns empty string when paths are equal.
   */
  public String relativize(ForwardRelativePath other) {
    int prefix = 0;
    for (; ; ) {
      if (prefix >= this.segments.length || prefix >= other.segments.length) {
        break;
      }
      if (this.segments[prefix] != other.segments[prefix]) {
        break;
      }
      prefix += 1;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = prefix; i != this.segments.length; ++i) {
      if (sb.length() != 0) {
        sb.append("/");
      }
      sb.append("..");
    }
    for (int i = prefix; i != other.segments.length; ++i) {
      if (sb.length() != 0) {
        sb.append("/");
      }
      sb.append(other.segments[i]);
    }
    return sb.toString();
  }

  private int hashCode;

  @Override
  public int hashCode() {
    // We do not have a luxury to keep a flag if hashCode() was ever invoked or not, so using
    // hashCode==0 for it. The poor guy (1 / 2^32 given good hash distribution) that resolves
    // to zero hash value will be always rehashed.
    if (hashCode == 0) {
      hashCode = Arrays.hashCode(segments);
    }
    return hashCode;
  }

  @Override
  public int compareTo(ForwardRelativePath that) {
    for (int i = 0; ; ++i) {
      if (this.segments.length == i || that.segments.length == i) {
        return Boolean.compare(this.segments.length != i, that.segments.length != i);
      }
      int compare = this.segments[i].compareTo(that.segments[i]);
      if (compare != 0) {
        return compare;
      }
    }
  }
}
