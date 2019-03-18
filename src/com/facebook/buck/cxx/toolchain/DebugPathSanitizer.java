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
package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.rules.modern.CustomFieldSerialization;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.StreamSupport;

/** Encapsulates all the logic to sanitize debug paths in native code. */
public abstract class DebugPathSanitizer implements AddsToRuleKey {

  @AddToRuleKey final boolean useUnixPathSeparator;

  /** @param useUnixPathSeparator use unix path separator in paths. */
  public DebugPathSanitizer(boolean useUnixPathSeparator) {
    this.useUnixPathSeparator = useUnixPathSeparator;
  }

  public DebugPathSanitizer() {
    this(false);
  }

  /** Custom serialization for the other field. */
  static class OtherSerialization
      implements CustomFieldSerialization<ImmutableBiMap<Path, String>> {
    @Override
    public <E extends Exception> void serialize(
        ImmutableBiMap<Path, String> value, ValueVisitor<E> serializer) throws E {
      serializer.visitInteger(value.size());
      for (Path path : value.keySet()) {
        serializer.visitString(path.toString());
      }
      for (String name : value.values()) {
        serializer.visitString(name);
      }
    }

    @Override
    public <E extends Exception> ImmutableBiMap<Path, String> deserialize(
        ValueCreator<E> deserializer) throws E {
      int size = deserializer.createInteger();
      ImmutableBiMap.Builder<Path, String> builder = ImmutableBiMap.builderWithExpectedSize(size);
      Path[] keys = new Path[size];
      for (int i = 0; i < size; i++) {
        keys[i] = Paths.get(deserializer.createString());
      }
      for (Path k : keys) {
        builder.put(k, deserializer.createString());
      }
      return builder.build();
    }
  }

  /**
   * @return the given path as a string, expanded using {@code separator} to fulfill the required
   *     {@code pathSize}.
   */
  public static String getPaddedDir(String path, int size, char pad) {
    Preconditions.checkArgument(
        path.length() <= size,
        String.format(
            "Path is too long to sanitize:\n'%s' is %d characters long, limit is %d.",
            path, path.length(), size));
    return Strings.padEnd(path, size, pad);
  }

  public abstract ImmutableMap<String, String> getCompilationEnvironment(
      Path workingDir, boolean shouldSanitize);

  @SuppressWarnings("unused")
  public ImmutableList<String> getCompilationFlags(
      Compiler compiler, Path workingDir, ImmutableMap<Path, Path> prefixMap) {
    return ImmutableList.of();
  }

  protected abstract Iterable<Map.Entry<Path, String>> getAllPaths(Optional<Path> workingDir);

  public abstract String getCompilationDirectory();

  public Function<String, String> sanitize(Optional<Path> workingDir) {
    return input -> DebugPathSanitizer.this.sanitize(workingDir, input);
  }

  public ImmutableList<String> sanitizeFlags(Iterable<String> flags) {
    return StreamSupport.stream(flags.spliterator(), false)
        .map(sanitize(Optional.empty()))
        .collect(ImmutableList.toImmutableList());
  }

  /**
   * @param workingDir the current working directory, if applicable.
   * @param contents the string to sanitize.
   * @return a string with all matching paths replaced with their sanitized versions.
   */
  public String sanitize(Optional<Path> workingDir, String contents) {
    for (Map.Entry<Path, String> entry : getAllPaths(workingDir)) {
      String replacement = entry.getValue();
      String pathToReplace =
          useUnixPathSeparator
              ? MorePaths.pathWithUnixSeparators(entry.getKey())
              : entry.getKey().toString();
      if (contents.contains(pathToReplace)) {
        // String.replace creates a number of objects, and creates a fair
        // amount of object churn at this level, so we avoid doing it if
        // it's essentially going to be a no-op.
        contents = contents.replace(pathToReplace, replacement);
      }
    }
    return contents;
  }

  // Construct the replacer, giving the expanded current directory and the desired directory.
  // We use ASCII, since all the relevant debug standards we care about (e.g. DWARF) use it.
  public abstract void restoreCompilationDirectory(Path path, Path workingDir) throws IOException;
}
