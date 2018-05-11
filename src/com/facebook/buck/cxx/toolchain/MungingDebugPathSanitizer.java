/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx.toolchain;

import static com.facebook.buck.cxx.toolchain.DebugSectionProperty.COMPRESSED;
import static com.facebook.buck.cxx.toolchain.DebugSectionProperty.STRINGS;
import static java.nio.channels.FileChannel.MapMode.READ_WRITE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.util.ByteBufferReplacer;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;

/**
 * This sanitizer works by munging the compiler output to replace paths. Currently, this just
 * supports sanitizing the compilation directory that compilers typically embed in binaries when
 * including debug sections (e.g. using "-g" with gcc/clang inserts the compilation directory in the
 * DW_AT_comp_dir DWARF field).
 */
public class MungingDebugPathSanitizer extends DebugPathSanitizer {
  private static final DebugSectionFinder DEBUG_SECTION_FINDER = new DebugSectionFinder();

  @AddToRuleKey(stringify = true)
  private final Path compilationDirectory;

  @AddToRuleKey private final int pathSize;
  @AddToRuleKey private final char separator;

  @CustomFieldBehavior(OtherSerialization.class)
  private final ImmutableBiMap<Path, String> other;

  /**
   * @param pathSize fix paths to this size for in-place replacements.
   * @param separator the path separator used to fill paths aren't of {@code pathSize} length.
   * @param compilationDirectory the desired path to replace the actual compilation directory with.
   */
  public MungingDebugPathSanitizer(
      int pathSize, char separator, Path compilationDirectory, ImmutableBiMap<Path, String> other) {
    Preconditions.checkState(!compilationDirectory.isAbsolute());
    this.compilationDirectory = compilationDirectory;
    this.pathSize = pathSize;
    this.separator = separator;
    this.other = other;
  }

  @Override
  public String getCompilationDirectory() {
    return getExpandedPath(compilationDirectory);
  }

  private String getExpandedPath(Path path) {
    return getPaddedDir(path.toString(), pathSize, separator);
  }

  @Override
  public ImmutableMap<String, String> getCompilationEnvironment(
      Path workingDir, boolean shouldSanitize) {
    // A forced compilation directory is set in the constructor.  Now, we can't actually force
    // the compiler to embed this into the binary -- all we can do set the PWD environment to
    // variations of the actual current working directory (e.g. /actual/dir or
    // /actual/dir////).  This adjustment serves two purposes:
    //
    //   1) it makes the compiler's current-directory line directive output agree with its cwd,
    //      given by getProjectDirectoryRoot.  (If PWD and cwd are different names for the same
    //      directory, the compiler prefers PWD, but we expect cwd for DebugPathSanitizer.)
    //
    //   2) in the case where we're using post-linkd debug path replacement, we reserve room
    //      to expand the path later.
    return ImmutableMap.of(
        "PWD", shouldSanitize ? getExpandedPath(workingDir) : workingDir.toString());
  }

  // Construct the replacer, giving the expanded current directory and the desired directory.
  // We use ASCII, since all the relevant debug standards we care about (e.g. DWARF) use it.
  @Override
  public void restoreCompilationDirectory(Path path, Path workingDir) throws IOException {
    restore(path, getCompilationDirectoryReplacer(workingDir));
  }

  /**
   * @return a {@link ByteBufferReplacer} suitable for replacing {@code workingDir} with {@code
   *     compilationDirectory}.
   */
  private ByteBufferReplacer getCompilationDirectoryReplacer(Path workingDir) {
    return new ByteBufferReplacer(
        ImmutableMap.of(
            getExpandedPath(workingDir).getBytes(Charsets.US_ASCII),
            getExpandedPath(compilationDirectory).getBytes(Charsets.US_ASCII)));
  }

  /**
   * Run {@code replacer} on all relevant debug sections in {@code buffer}, falling back to
   * processing the entire {@code buffer} if the format is unrecognized.
   */
  private void restore(ByteBuffer buffer, ByteBufferReplacer replacer) {
    // Find the debug sections in the file represented by the buffer.
    Optional<ImmutableMap<String, DebugSection>> results = DEBUG_SECTION_FINDER.find(buffer);

    // If we were able to recognize the file format and find debug symbols, perform the
    // replacement on them.  Otherwise, just do a find-and-replace on the whole blob.
    if (results.isPresent()) {
      for (DebugSection section : results.get().values()) {
        // We can't do in-place updates on compressed debug sections.
        Preconditions.checkState(!section.properties.contains(COMPRESSED));
        if (section.properties.contains(STRINGS)) {
          replacer.replace(section.body);
        }
      }
    } else {
      replacer.replace(buffer);
    }
  }

  protected void restore(Path path, ByteBufferReplacer replacer) throws IOException {
    try (FileChannel channel = FileChannel.open(path, READ, WRITE)) {
      MappedByteBuffer buffer = channel.map(READ_WRITE, 0, channel.size());
      restore(buffer, replacer);
    }
  }

  @Override
  protected Iterable<Map.Entry<Path, String>> getAllPaths(Optional<Path> workingDir) {
    if (!workingDir.isPresent()) {
      return other.entrySet();
    }
    return Iterables.concat(
        other.entrySet(),
        ImmutableList.of(
            new AbstractMap.SimpleEntry<>(workingDir.get(), compilationDirectory.toString())));
  }
}
