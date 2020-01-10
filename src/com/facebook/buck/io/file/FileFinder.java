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

package com.facebook.buck.io.file;

import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** Methods for finding files. */
public class FileFinder {

  /**
   * Combines prefixes, base, and suffixes to create a set of file names.
   *
   * @param prefixes set of prefixes. May be empty.
   * @param base base name. May be empty.
   * @param suffixes set of suffixes. May be empty.
   * @return a set containing all combinations of prefix, base, and suffix.
   */
  public static ImmutableSet<String> combine(
      Set<String> prefixes, String base, Set<String> suffixes) {

    ImmutableSet<String> suffixedSet;
    if (suffixes.isEmpty()) {
      suffixedSet = ImmutableSet.of(base);
    } else {
      ImmutableSet.Builder<String> suffixedBuilder = ImmutableSet.builder();
      for (String suffix : suffixes) {
        suffixedBuilder.add(base + suffix);
      }
      suffixedSet = suffixedBuilder.build();
    }

    if (prefixes.isEmpty()) {
      return suffixedSet;
    } else {
      ImmutableSet.Builder<String> builder = ImmutableSet.builder();
      for (String prefix : prefixes) {
        for (String suffix : suffixedSet) {
          builder.add(prefix + suffix);
        }
      }
      return builder.build();
    }
  }

  /**
   * Tries to find a file with one of a number of possible names in a search path.
   *
   * <p>Returns the first match found. Search tries all paths in the search paths in order, looking
   * for any matching names in each path.
   *
   * @param possibleNames file names to look for.
   * @param searchPaths directories to search.
   * @param filter additional check that discovered paths must pass to be eligible.
   * @return returns the first match found, if any.
   */
  public static Optional<Path> getOptionalFile(
      Set<String> possibleNames, Iterable<Path> searchPaths, Predicate<Path> filter) {

    return RichStream.from(searchPaths)
        .flatMap(searchPath -> possibleNames.stream().map(searchPath::resolve))
        .filter(filter)
        .findFirst();
  }

  /** Constructor hidden; there is no reason to instantiate this class. */
  private FileFinder() {}
}
