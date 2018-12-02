/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.cli.bootstrapper.filesystem;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchEvent.Modifier;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Buck-specific implementation of java.nio.file.Path optimized for memory footprint */
public class BuckUnixPath implements Path {
  // Constant strings are already interned, but having the constant here makes it more obvious in
  // code below.
  private static final String DOTDOT = "..";
  private static final String DOT = ".";
  private static final String ROOT = "";

  // Java's memory layout is padded to 8 bytes on most implementations. Given that 12 bytes is
  // a class header, we can use up to 3 4-byte fields to fit into 24-byte object. Reference type
  // is 4 bytes on heaps < 32Gb on most implementations, so for memory footprint it makes sense
  // to have either 1 or three 4-byte fields.
  private final String[] segments;
  private final BuckFileSystem fs;
  private volatile int hashCode = 0;

  // segments should already be interned.
  private BuckUnixPath(BuckFileSystem fs, String[] segments) {
    this.fs = fs;
    this.segments = segments;
  }

  /**
   * Create a new instance of BuckUnixPath. The implementation may use interning.
   *
   * @param fs Filesystem that created this instance
   * @param path String representation of a path
   */
  public static BuckUnixPath of(BuckFileSystem fs, String path) {
    if (path.isEmpty()) {
      return fs.getEmptyPath();
    }
    if (path.equals("/")) {
      return fs.getRootDirectory();
    }
    return new BuckUnixPath(fs, intern(normalizeAndCheck(path).split("/")));
  }

  private static String[] intern(String[] segments) {
    // using plain old loops for performance
    for (int i = 0; i < segments.length; i++) {
      segments[i] = segments[i].intern();
    }
    return segments;
  }

  static BuckUnixPath rootOf(BuckFileSystem fs) {
    return new BuckUnixPath(fs, new String[] {ROOT});
  }

  static BuckUnixPath emptyOf(BuckFileSystem fs) {
    return new BuckUnixPath(fs, new String[0]);
  }

  /** Return Java default implementation of Path inferred from current instance */
  Path asDefault() {
    return fs.getDefaultFileSystem().getPath(toString());
  }

  /** Remove redundant slashes and check input for invalid characters */
  private static String normalizeAndCheck(String input) {
    int n = input.length();
    char prevChar = 0;
    for (int i = 0; i < n; i++) {
      char c = input.charAt(i);
      if ((c == '/') && (prevChar == '/')) {
        return normalize(input, n, i - 1);
      }
      checkNotNul(input, c);
      prevChar = c;
    }
    if (prevChar == '/') {
      return normalize(input, n, n - 1);
    }
    return input;
  }

  private static void checkNotNul(String input, char c) {
    if (c == '\u0000') {
      throw new InvalidPathException(input, "Nul character not allowed");
    }
  }

  private static String normalize(String input, int len, int off) {
    if (len == 0) {
      return input;
    }
    int n = len;
    while ((n > 0) && (input.charAt(n - 1) == '/')) {
      n--;
    }
    if (n == 0) {
      return "/";
    }
    StringBuilder sb = new StringBuilder(input.length());
    if (off > 0) {
      sb.append(input, 0, off);
    }
    char prevChar = 0;
    for (int i = off; i < n; i++) {
      char c = input.charAt(i);
      if ((c == '/') && (prevChar == '/')) {
        continue;
      }
      checkNotNul(input, c);
      sb.append(c);
      prevChar = c;
    }
    return sb.toString();
  }

  // Convert given path to BuckUnixPath
  private BuckUnixPath toUnixPath(Path obj) {
    if (obj == null) {
      throw new NullPointerException();
    }

    if (obj instanceof BuckUnixPath) {
      return (BuckUnixPath) obj;
    }

    FileSystem otherFs = obj.getFileSystem();
    if (!fs.equals(otherFs) && !fs.getDefaultFileSystem().equals(otherFs)) {
      throw new ProviderMismatchException(
          "Unable to convert Path to BuckUnixPath because file systems do not match");
    }
    return BuckUnixPath.of(fs, obj.toString());
  }

  /** @return {@code true} if this path is an empty path */
  private boolean isEmpty() {
    return segments.length == 0;
  }

  /** @return an empty path */
  private BuckUnixPath emptyPath() {
    return fs.getEmptyPath();
  }

  @Override
  public FileSystem getFileSystem() {
    return fs;
  }

  @Override
  public Path getRoot() {
    if (segments.length > 0 && segments[0].isEmpty()) {
      return fs.getRootDirectory();
    }
    return null;
  }

  @Override
  public Path getFileName() {
    if (isEmpty()) {
      return null;
    }
    if (isAbsolute() && segments.length == 1) {
      return fs.getRootDirectory();
    }
    return new BuckUnixPath(fs, new String[] {segments[segments.length - 1]});
  }

  @Override
  public Path getParent() {
    if (segments.length < 2) {
      return null;
    }
    if (segments[segments.length - 2].isEmpty()) {
      return getRoot();
    }
    return new BuckUnixPath(fs, Arrays.copyOf(segments, segments.length - 1));
  }

  @Override
  public int getNameCount() {
    if (isEmpty()) {
      return 0;
    }
    return segments.length - (isAbsolute() ? 1 : 0);
  }

  @Override
  public Path getName(int index) {
    if (index < 0) {
      throw new IllegalArgumentException();
    }

    index += isAbsolute() ? 1 : 0;

    if (index >= segments.length) {
      throw new IllegalArgumentException();
    }

    return new BuckUnixPath(fs, new String[] {segments[index]});
  }

  @Override
  public BuckUnixPath subpath(int beginIndex, int endIndex) {
    if (beginIndex < 0) {
      throw new IllegalArgumentException();
    }

    int offset = isAbsolute() ? 1 : 0;
    beginIndex += offset;
    endIndex += offset;

    if (beginIndex >= segments.length) {
      throw new IllegalArgumentException();
    }
    if (endIndex > segments.length) {
      throw new IllegalArgumentException();
    }
    if (beginIndex >= endIndex) {
      throw new IllegalArgumentException();
    }

    return new BuckUnixPath(fs, Arrays.copyOfRange(segments, beginIndex, endIndex));
  }

  @Override
  public boolean isAbsolute() {
    return (!isEmpty() && segments[0].isEmpty());
  }

  @Override
  public Path resolve(Path obj) {
    BuckUnixPath other = toUnixPath(obj);

    if (other.isEmpty()) {
      return this;
    }

    if (isEmpty() || other.isAbsolute()) {
      return other;
    }

    return new BuckUnixPath(fs, concatSegments(segments, other.segments));
  }

  @Override
  public Path resolve(String other) {
    return resolve(fs.getPath(other));
  }

  @Override
  public Path resolveSibling(Path other) {
    if (other == null) {
      throw new NullPointerException();
    }
    Path parent = getParent();
    return parent == null ? other : parent.resolve(other);
  }

  @Override
  public Path resolveSibling(String other) {
    return resolveSibling(fs.getPath(other));
  }

  private String[] concatSegments(String[] first, String[] second) {
    String[] result = new String[first.length + second.length];
    System.arraycopy(first, 0, result, 0, first.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  @Override
  public Path relativize(Path obj) {
    BuckUnixPath other = toUnixPath(obj);

    if (other.equals(this)) {
      return emptyPath();
    }

    // can only relativize paths of the same type
    if (this.isAbsolute() != other.isAbsolute()) {
      throw new IllegalArgumentException("Both paths should be either absolute paths or not");
    }

    // this path is the empty path
    if (this.isEmpty()) {
      return other;
    }

    // skip matching names
    int minCount =
        (segments.length > other.segments.length) ? other.segments.length : segments.length;

    int i = 0;
    for (; i < minCount; i++) {
      // intentional reference compare
      if (segments[i] != other.segments[i]) {
        break;
      }
    }

    int dotdots = segments.length - i;
    if (i >= other.segments.length) {
      // no remaining sections in other so result is simply a sequence of ".."
      String[] newSegments = new String[dotdots];
      Arrays.fill(newSegments, DOTDOT);
      return new BuckUnixPath(fs, newSegments);
    }

    // result is a  "../" for each remaining name in base
    // followed by the remaining names in other.
    String[] newSegments = new String[dotdots + other.segments.length - i];
    Arrays.fill(newSegments, 0, dotdots, DOTDOT);
    System.arraycopy(other.segments, i, newSegments, dotdots, other.segments.length - i);
    return new BuckUnixPath(fs, newSegments);
  }

  @Override
  public Path normalize() {
    if (isEmpty()) {
      return this;
    }

    // segments are interned, so using == instead of .equals() to compare strings for performance
    // if different interner is used, this function will be broken!

    // first quick pass to check if anything needs to be normalized
    int i = segments.length - 1;
    for (; i >= 0; i--) {
      // intentional reference compare
      if (segments[i] == DOT || segments[i] == DOTDOT) {
        break;
      }
    }

    if (i < 0) {
      return this;
    }

    // it seems there are symbols like ".." in the path that should be normalized

    // have to allocate maximum length as some of the dots (leading ones) might not be removed
    String[] filtered = new String[segments.length];

    // copy the tail which is good
    System.arraycopy(segments, i + 1, filtered, i + 1, segments.length - i - 1);

    // continue going down the array removing non-meaningful segments
    int dotdots = 0;
    int j = i;
    for (; i >= 0; i--) {
      String segment = segments[i];

      // intentional reference compare
      if (segment == DOT) {
        continue;
      }

      // intentional reference compare
      if (segment == DOTDOT) {
        dotdots++;
        continue;
      }

      if (dotdots > 0) {
        // ignore real segment because it is swallowed by following dotdot
        dotdots--;
        continue;
      }

      filtered[j] = segment;
      j--;
    }

    if (dotdots > 0) {
      // some leading dotdots left, so copy them to the resulting array
      j -= dotdots;
      Arrays.fill(filtered, j + 1, j + 1 + dotdots, DOTDOT);
    }

    if (isAbsolute() && (j + 1 >= filtered.length || filtered[j + 1] != ROOT)) {
      // Root segment was removed by .., restore it back
      filtered[j] = DOTDOT;
      filtered[j - 1] = ROOT;
      j -= 2;
    }

    if (j >= 0) {
      filtered = Arrays.copyOfRange(filtered, j + 1, filtered.length);
    }

    return new BuckUnixPath(fs, filtered);
  }

  @Override
  public boolean startsWith(Path other) {
    return compareSegmentsFrom(other, true);
  }

  @Override
  public boolean endsWith(Path other) {
    return compareSegmentsFrom(other, false);
  }

  private boolean compareSegmentsFrom(Path other, boolean startOrEnd) {
    if (!(Objects.requireNonNull(other) instanceof BuckUnixPath)) {
      return false;
    }

    BuckUnixPath that = toUnixPath(other);

    if (isEmpty() || that.isEmpty()) {
      return isEmpty() && that.isEmpty();
    }

    if (that.segments.length > segments.length) {
      return false;
    }

    int start = startOrEnd ? 0 : (segments.length - that.segments.length);

    for (int i = 0; i < that.segments.length; i++) {
      // intentional reference compare
      if (segments[i + start] != that.segments[i]) {
        return false;
      }
    }

    return true;
  }

  @Override
  public boolean startsWith(String other) {
    return startsWith(fs.getPath(other));
  }

  @Override
  public boolean endsWith(String other) {
    return endsWith(fs.getPath(other));
  }

  @Override
  public int compareTo(Path other) {
    if (other instanceof BuckUnixPath) {
      return compareTo((BuckUnixPath) other);
    }
    return toString().compareTo(other.toString());
  }

  /** Lexicographically compares another path to this one */
  public int compareTo(BuckUnixPath other) {
    // lexicographic ordering just like {@link String#compareTo}
    int lim = Math.min(this.segments.length, other.segments.length);
    for (int i = 0; i < lim; i++) {
      int res = segments[i].compareTo(other.segments[i]);
      if (res != 0) {
        return res;
      }
    }
    return segments.length - other.segments.length;
  }

  @Override
  public boolean equals(Object ob) {
    if (!(ob instanceof BuckUnixPath)) {
      return false;
    }

    String[] otherSegments = ((BuckUnixPath) ob).segments;
    if (segments.length != otherSegments.length) {
      return false;
    }

    for (int i = 0; i < segments.length; i++) {
      // intentional reference compare
      if (segments[i] != otherSegments[i]) {
        return false;
      }
    }

    return true;
  }

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
  public String toString() {
    if (isEmpty()) {
      return "";
    }
    if (isAbsolute() && segments.length == 1) {
      return "/";
    }
    if (segments.length == 1) {
      return segments[0];
    }
    return String.join("/", segments);
  }

  // resolve current path against default path
  private Path resolveDefault() {
    BuckUnixPath defDir = fs.getDefaultDirectory();
    return defDir.resolve(this);
  }

  @Override
  public Path toAbsolutePath() {
    if (isAbsolute()) {
      return this;
    }

    return resolveDefault();
  }

  @Override
  public Path toRealPath(LinkOption... options) throws IOException {
    // TODO(buck_team): reimplement this
    Path path = asDefault().toRealPath(options);
    return BuckUnixPath.of(fs, path.toString());
  }

  @Override
  public File toFile() {
    return new File(toString());
  }

  @Override
  public URI toUri() {
    // TODO(buck_team): do not recourse to default Path implementation
    return asDefault().toUri();
  }

  @Override
  public WatchKey register(
      WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers)
      throws IOException {
    // TODO(buck_team): do not recourse to default Path implementation
    return asDefault().register(watcher, events, modifiers);
  }

  @Override
  public WatchKey register(WatchService watcher, Kind<?>... events) throws IOException {
    return this.register(watcher, events, new Modifier[0]);
  }

  @Override
  public Iterator<Path> iterator() {
    return new Iterator<Path>() {
      private int i = 0;

      @Override
      public boolean hasNext() {
        return i < getNameCount();
      }

      @Override
      public Path next() {
        if (i >= getNameCount()) {
          throw new NoSuchElementException();
        }
        Path current = getName(i);
        i++;
        return current;
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException();
      }
    };
  }
}
