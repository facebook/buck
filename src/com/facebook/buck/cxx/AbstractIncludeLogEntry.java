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

package com.facebook.buck.cxx;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Function;
import java.util.Optional;
import java.util.SortedMap;
import org.immutables.value.Value;

/**
 * Represents an entry in an IncludeLog instance, i.e. each "inclusion event" during the
 * generation of a source file's preprocessed output.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractIncludeLogEntry {

  public static enum InclusionKind {
    INCLUDE,
    INCLUDE_NEXT,
    IMPORT,
    INCLUDE_MACROS,
    BUILTIN,
    ;
  }

  public static enum QuoteKind {
    QUOTE,
    ANGLE,
    /** in the case of a {@link InclusionKind#BUILTIN} */
    NONE,
    ;
  }

  /**
   * Indicates order of inclusions happening.  Just an increasing number assigned to each.
   * Is unique so that lower id means it happened before another IncludeLogEntry.
   */
  @Value.Auxiliary
  @Value.Parameter
  public abstract int id();

  /**
   * The inclusion event that led to the inclusion of _this_ one's includer.
   * E.g. if "foo.h" includes "bar.h", the parent is the inclusion event for, e.g.
   * when "foo.cpp" included "foo.h".  If included by the main file, this is not present.
   */
  @Value.Parameter
  public abstract Optional<IncludeLogEntry> getParent();

  /**
   * Which file did the inclusion.
   *
   * See {@link MorePaths#fixPath(Path)} for how the path given to the constructor is
   * cleaned up prior to memoizing it here.
   */
  @Value.Auxiliary
  @Value.Parameter
  public abstract Path getOrigCurrentFile();

  @Value.Derived
  public Path getCurrentFile() {
    return MorePaths.fixPath(getOrigCurrentFile());
  }

  /**
   * String representing the kind of inclusion performed.  Known examples:
   * <ul>
   * <li><code>#include</code></li>
   * <li><code>#include_next</code></li>
   * <li><code>#import</code> if you're into Objective-C/C++</li>
   * <li><code>#__include_macros</code> produced by Clang if you did {@code -imacros}
   *     on the command line.</li>
   * <ul>
   */
  @Value.Parameter
  public abstract InclusionKind getInclusionKind();

  /** how this was included, i.e. {@code #include &lt;foo&gt; or #include "bar.h"}. */
  @Value.Parameter
  public abstract QuoteKind getQuoteKind();

  /**
   * The path that was included, in a literal sense.  Examples:
   *
   * <ul>
   * <li>./foo.c does {@code #include "bar.h"}: result is {@code bar.h}</li>
   * <li>./foo.c does {@code #include "./bar.h"}: result is {@code ./bar.h}</li>
   * <li>somepath/foo.c does {@code #include "bar.h"}: result is {@code bar.h}</li>
   * </ul>
   *
   * <p>There's little logic in determining this string; it's just the text between quote
   * marks / angle brackets.
   * </p>
   */
  @Value.Parameter
  public abstract String getParameter();

  /**
   * The path that was included, after resolution w/r/t the includer.
   * Only affects quoted includes.
   *
   * <ul>
   * <li>./foo.c does {@code #include "bar.h"}:
   *     result is {@code bar.h} for gcc, {@code ./bar.h} for clang.</li>
   * <li>./foo.c does {@code #include "./bar.h"}: result is {@code ./bar.h}</li>
   * <li>somepath/foo.c does {@code #include "bar.h"}, where "bar.h" is in the same dir as foo.c:
   *     result is {@code somepath/bar.h}</li>
   * </ul>
   *
   * See {@link MorePaths#fixPath(Path)} for how the path given to the constructor is
   * cleaned up prior to memoizing it here.
   */
  @Value.Auxiliary
  @Value.Parameter
  public abstract Path getOrigNextFile();

  /**
   * The path that was included, after resolution w/r/t the includer.
   * See {@link #getOrigNextFile()}.
   * See {@link MorePaths#fixPath(Path)} for how the path given to the constructor is
   * cleaned up prior to memoizing it here.
   */
  @Value.Derived
  public Path getNextFile() {
    return MorePaths.fixPath(getOrigNextFile());
  }

  public void write(PrintWriter out) {
    final int parentID = this.getParent().isPresent() ? this.getParent().get().id() : -1;
    out.printf(
        "%d\t%d\t%s\t%s\t%s\t%s\t%s\n",
        id(),
        parentID,
        getCurrentFile().toString(),
        getInclusionKind().toString(),
        getQuoteKind().toString(),
        getParameter(),
        getNextFile().toString());
  }

  public static IncludeLogEntry read(
      String line,
      SortedMap<Integer, IncludeLogEntry> entryTable) {
    line = line.trim();
    String[] parts = line.split("\t");
    Preconditions.checkState(parts.length == 7);

    Optional<IncludeLogEntry> parent;
    int parentID = Integer.parseInt(parts[1]);
    if (parentID == -1) {
      parent = Optional.empty();
    } else {
      parent = Optional.of(Preconditions.checkNotNull(entryTable.get(parentID)));
    }

    return IncludeLogEntry.of(
        Integer.parseInt(parts[0]),
        parent,
        Paths.get(parts[2]),
        InclusionKind.valueOf(parts[3]),
        QuoteKind.valueOf(parts[4]),
        parts[5],
        Paths.get(parts[6]));
  }

  /**
   * Attempt to resolve this hypothetical included file against the given path list.
   */
  private Optional<Path> tryResolveUsingPaths(
      ImmutableList<Path> paths,
      Optional<Path> ignoreUntilAfterPath,
      Function<Path, Boolean> validate) {

    // trivial case: included an absolute path.  No logic to apply; absolute includes use no paths.
    if (Paths.get(getParameter()).isAbsolute()) {
      final Path absolute = Paths.get(getParameter());
      if (validate.apply(absolute)) {
        return Optional.of(absolute);
      } else {
        return Optional.empty();
      }
    }

    for (Path path : paths) {
      if (ignoreUntilAfterPath.isPresent()) {
        if (ignoreUntilAfterPath.get().equals(path)) {
          ignoreUntilAfterPath = Optional.<Path>empty();
        }
        continue;
      }
      Path checkHeader = path.resolve(getParameter());
      if (validate.apply(checkHeader)) {
        return Optional.of(checkHeader);
      }
    }

    return Optional.empty();
  }

  private Path getEffectivePath() {
    // example:
    // #include "foo/bar.h" resolved to "some/path/foo/bar.h".
    // Strip off the "foo/bar.h" from the end of the resolved path, to get the apparent path used
    // to resolve the inclusion.
    Path ret = getCurrentFile();
    int stripCount = Paths.get(this.getParameter()).getNameCount();
    for (int i = 0; i < stripCount && ret.getNameCount() > 1; i++) {
      ret = ret.getParent();
    }
    return ret;
  }

  public boolean tryResolveUsingPaths(ImmutableList<Path> paths, Function<Path, Boolean> validate) {
    Optional<Path> result =
        tryResolveUsingPaths(
            paths,
            getInclusionKind() == InclusionKind.INCLUDE_NEXT
                ? Optional.of(getEffectivePath()) : Optional.<Path>empty(),
            validate);
    return result.isPresent() && result.get().equals(this.getNextFile());
  }

  public boolean tryResolveUsingPaths(ImmutableList<Path> paths) {
    return tryResolveUsingPaths(paths, Files::isReadable);
  }

}
