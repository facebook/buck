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

package com.facebook.buck.skylark.io.impl;

import com.facebook.buck.io.watchman.WatchmanClient;
import com.facebook.buck.io.watchman.WatchmanQuery;
import com.facebook.buck.io.watchman.WatchmanQueryFailedException;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * An implementation of globbing functionality that allows resolving file paths based on include
 * patterns (file patterns that should be returned) minus exclude patterns (file patterns that
 * should be excluded from the resulting set) using Watchman tool for improved performance.
 *
 * <p>The implementation is mostly compatible with glob_watchman.py and as such differs from the
 * {@link NativeGlobber} in certain ways:
 *
 * <ul>
 *   <li>does not fail for patterns that cannot possibly match
 *   <li>does not collapse multiple slashes
 * </ul>
 */
public class WatchmanGlobber {

  /**
   * Watchman options to use when globbing.
   *
   * @see WatchmanGlobber#run(Collection, Collection, EnumSet, long, long)
   */
  public enum Option {
    /**
     * Do not return directories which match include patterns.
     *
     * <p>Symlinks referring to directories are still returned unless {@link
     * Option#EXCLUDE_SYMLINKS} is also specified.
     *
     * <p>This option corresponds to a <a
     * href="https://facebook.github.io/watchman/docs/expr/type.html">{@code type} expression</a>
     * which excludes directories.
     */
    EXCLUDE_DIRECTORIES,
    /**
     * Do not return regular files which match include patterns.
     *
     * <p>This option corresponds to a <a
     * href="https://facebook.github.io/watchman/docs/expr/type.html">{@code type} expression</a>
     * which excludes directories.
     */
    EXCLUDE_REGULAR_FILES,
    /**
     * Do not return symbolic links which match include patterns.
     *
     * <p>Without this option, symbolic links are returned, regardless of their target.
     *
     * <p>This option corresponds to a <a
     * href="https://facebook.github.io/watchman/docs/expr/type.html">{@code type} expression</a>
     * which excludes symbolic links.
     */
    EXCLUDE_SYMLINKS,
    /**
     * Match path components exactly, even on case-insensitive file systems.
     *
     * <p>By default, whether or not patterns ignore case depends on <a
     * href="https://facebook.github.io/watchman/docs/cmd/query.html#case-sensitivity">Watchman's
     * default behavior</a>.
     *
     * <p>This option affects both include patterns and exclude patterns.
     *
     * <p>This option corresponds to the query's <a
     * href="https://facebook.github.io/watchman/docs/cmd/query.html#case-sensitivity">{@code
     * case_sensitive} option</a> to {@code true}.
     */
    FORCE_CASE_SENSITIVE,
  }

  /** A map-like class represents the file attributes queried from watchman globber */
  public static class WatchmanFileAttributes {
    private final ImmutableMap<String, ?> attributeMap;

    public WatchmanFileAttributes(Map<String, ?> attributeMap) {
      this.attributeMap = ImmutableMap.copyOf(attributeMap);
    }

    public ImmutableMap<String, ?> getAttributeMap() {
      return attributeMap;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (!(obj instanceof WatchmanFileAttributes)) {
        return false;
      }
      WatchmanFileAttributes that = (WatchmanFileAttributes) obj;
      return this.attributeMap.equals(that.getAttributeMap());
    }

    @Override
    public int hashCode() {
      return attributeMap.hashCode();
    }
  }

  private static final long DEFAULT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
  private static final long DEFAULT_WARN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(1);
  private static final ImmutableList<String> NAME_ONLY_FIELD = ImmutableList.of("name");
  private final WatchmanClient watchmanClient;
  /** Path used as a root when resolving patterns. */
  private final String basePath;

  private final String watchmanWatchRoot;
  private final SyncCookieState syncCookieState;

  private WatchmanGlobber(
      WatchmanClient watchmanClient,
      String basePath,
      String watchmanWatchRoot,
      SyncCookieState syncCookieState) {
    this.watchmanClient = watchmanClient;
    this.basePath = basePath;
    this.watchmanWatchRoot = watchmanWatchRoot;
    this.syncCookieState = syncCookieState;
  }

  /**
   * @param include File patterns that should be included in the resulting set.
   * @param exclude File patterns that should be excluded from the resulting set.
   * @param excludeDirectories Whether directories should be excluded from the resulting set.
   * @return The set of paths resolved using include patterns minus paths excluded by exclude
   *     patterns.
   * @see WatchmanGlobber#run(Collection, Collection, EnumSet, long, long)
   */
  public Optional<ImmutableSet<String>> run(
      Collection<String> include, Collection<String> exclude, boolean excludeDirectories)
      throws IOException, InterruptedException {
    return run(
        include,
        exclude,
        excludeDirectories ? EnumSet.of(Option.EXCLUDE_DIRECTORIES) : EnumSet.noneOf(Option.class),
        DEFAULT_TIMEOUT_NANOS,
        DEFAULT_WARN_TIMEOUT_NANOS);
  }

  public Optional<ImmutableSet<String>> run(
      Collection<String> include, Collection<String> exclude, Option option)
      throws IOException, InterruptedException {
    return run(
        include, exclude, EnumSet.of(option), DEFAULT_TIMEOUT_NANOS, DEFAULT_WARN_TIMEOUT_NANOS);
  }

  public Optional<ImmutableSet<String>> run(
      Collection<String> include, Collection<String> exclude, EnumSet<Option> options)
      throws IOException, InterruptedException {
    return run(include, exclude, options, DEFAULT_TIMEOUT_NANOS, DEFAULT_WARN_TIMEOUT_NANOS);
  }

  /**
   * @param include File patterns that should be included in the resulting set.
   * @param exclude File patterns that should be excluded from the resulting set.
   * @param options Customizations for matching behavior.
   * @param timeoutNanos timeout in nanoseconds
   * @param warnTimeNanos time to polling results if query is slow
   * @return The set of paths resolved using include patterns minus paths excluded by exclude
   *     patterns.
   * @throws WatchmanQueryFailedException Watchman returned an error response.
   */
  public Optional<ImmutableSet<String>> run(
      Collection<String> include,
      Collection<String> exclude,
      EnumSet<Option> options,
      long timeoutNanos,
      long warnTimeNanos)
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanQuery =
        createNameOnlyWatchmanQuery(include, exclude, options);

    Either<Map<String, Object>, WatchmanClient.Timeout> result =
        performWatchmanQuery(timeoutNanos, warnTimeNanos, watchmanQuery);
    if (!result.isLeft()) {
      return Optional.empty();
    }

    @Nullable Object error = result.getLeft().get("error");
    if (error != null) {
      throw new WatchmanQueryFailedException(error.toString());
    }
    @SuppressWarnings("unchecked")
    List<String> files = (List<String>) result.getLeft().get("files");
    return Optional.of(ImmutableSet.copyOf(files));
  }

  private Either<Map<String, Object>, WatchmanClient.Timeout> performWatchmanQuery(
      long timeoutNanos, long pollingTimeNanos, ImmutableMap<String, Object> watchmanQuery)
      throws IOException, InterruptedException {
    Either<Map<String, Object>, WatchmanClient.Timeout> result =
        watchmanClient.queryWithTimeout(
            timeoutNanos, pollingTimeNanos, WatchmanQuery.query(watchmanWatchRoot, watchmanQuery));
    if (result.isLeft()) {
      // Disable sync cookies only on successful query.
      this.syncCookieState.disableSyncCookies();
    }
    return result;
  }

  /**
   * If only query file names, watchman returns [ "filename1", "filename2", "filename3" ... ]
   *
   * <p>If queries more than file name, i.e. size, watchman returns [ { "name": filename1, "size":
   * 12345 }, { "name": filename2, "size": 123456 }, ... ]
   *
   * <p>This function will convert both query into a map like [ "filename1": { "name": "filename1",
   * "size": 12345 } ]
   *
   * <p>This function does not check the fields is valid due to its performance. The capacity can be
   * checked by command "watchman list-capabilities"
   *
   * @param includePatterns File patterns that should be included in the resulting set.
   * @param excludePatterns File patterns that should be excluded from the resulting set.
   * @param options Customizations for matching behavior.
   * @param timeoutNanos timeout in nanoseconds
   * @param warnTimeNanos time to polling results if query is slow
   * @param fields Fields to query
   * @return a optional map of matching file names to their file properties.
   * @throws IOException
   * @throws InterruptedException
   */
  public Optional<ImmutableMap<String, WatchmanFileAttributes>> runWithExtraFields(
      Collection<String> includePatterns,
      Collection<String> excludePatterns,
      EnumSet<Option> options,
      long timeoutNanos,
      long warnTimeNanos,
      ImmutableList<String> fields)
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanQuery =
        createWatchmanQuery(includePatterns, excludePatterns, options, fields);
    Preconditions.checkArgument(!fields.isEmpty());

    if (fields.equals(NAME_ONLY_FIELD)) {
      Optional<ImmutableSet<String>> nameSet =
          run(includePatterns, excludePatterns, options, timeoutNanos, warnTimeNanos);
      if (nameSet.isPresent()) {
        ImmutableMap<String, WatchmanFileAttributes> resultMap =
            nameSet.get().stream()
                .collect(
                    ImmutableMap.toImmutableMap(
                        Function.identity(),
                        name -> new WatchmanFileAttributes(ImmutableMap.of("name", name))));
        return Optional.of(resultMap);
      } else {
        return Optional.empty();
      }
    }

    Either<Map<String, Object>, WatchmanClient.Timeout> result =
        performWatchmanQuery(timeoutNanos, warnTimeNanos, watchmanQuery);
    if (!result.isLeft()) {
      return Optional.empty();
    }

    @Nullable Object error = result.getLeft().get("error");
    if (error != null) {
      throw new WatchmanQueryFailedException(error.toString());
    }
    @SuppressWarnings("unchecked")
    List<Map<String, ?>> resultEntries = (List<Map<String, ?>>) result.getLeft().get("files");
    ImmutableMap<String, WatchmanFileAttributes> resultMap =
        resultEntries.stream()
            .filter(
                entry ->
                    entry.containsKey("name") && entry.values().stream().allMatch(v -> v != null))
            .collect(
                ImmutableMap.toImmutableMap(
                    entry -> (String) entry.get("name"), WatchmanFileAttributes::new));
    return resultMap.isEmpty() ? Optional.empty() : Optional.of(resultMap);
  }

  private ImmutableMap<String, Object> createNameOnlyWatchmanQuery(
      Collection<String> include, Collection<String> exclude, EnumSet<Option> options) {
    return createWatchmanQuery(include, exclude, options, NAME_ONLY_FIELD);
  }

  /**
   * Creates a JSON-like Watchman query to get a list of matching files.
   *
   * <p>The implementation should ideally match the one in glob_watchman.py.
   */
  private ImmutableMap<String, Object> createWatchmanQuery(
      Collection<String> include,
      Collection<String> exclude,
      EnumSet<Option> options,
      ImmutableList<String> fileds) {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    builder.putAll(
        ImmutableMap.of(
            "relative_root",
            basePath,
            "expression",
            toMatchExpressions(exclude, options),
            "glob",
            include,
            "fields",
            fileds));
    if (options.contains(Option.FORCE_CASE_SENSITIVE)) {
      builder.put("case_sensitive", true);
    }

    // Sync cookies cause a massive overhead when issuing thousands of
    // glob queries.  Only enable them (by not setting sync_timeout to 0)
    // for the very first request issued by this process.
    if (!syncCookieState.shouldSyncCookies()) {
      builder.put("sync_timeout", 0);
    }

    return builder.build();
  }

  /** Returns an expression for every matched include file should match in order to be returned. */
  private static ImmutableList<Object> toMatchExpressions(
      Collection<String> exclude, EnumSet<Option> options) {
    ImmutableList.Builder<Object> matchExpressions = ImmutableList.builder();
    matchExpressions.add("allof", toTypeExpression(options));
    if (!exclude.isEmpty()) {
      matchExpressions.add(toExcludeExpression(exclude));
    }
    return matchExpressions.build();
  }

  /** Returns an expression for matching types of files to return. */
  private static ImmutableList<Object> toTypeExpression(EnumSet<Option> options) {
    ImmutableList.Builder<Object> typeExpressionBuilder = ImmutableList.builder().add("anyof");

    if (!options.contains(Option.EXCLUDE_REGULAR_FILES)) {
      typeExpressionBuilder.add(ImmutableList.of("type", "f"));
    }
    if (!options.contains(Option.EXCLUDE_DIRECTORIES)) {
      typeExpressionBuilder.add(ImmutableList.of("type", "d"));
    }
    if (!options.contains(Option.EXCLUDE_SYMLINKS)) {
      typeExpressionBuilder.add(ImmutableList.of("type", "l"));
    }
    return typeExpressionBuilder.build();
  }

  /** Returns an expression that excludes all paths in {@code exclude}. */
  private static ImmutableList<Serializable> toExcludeExpression(Collection<String> exclude) {
    return ImmutableList.of(
        "not",
        ImmutableList.builder()
            .add("anyof")
            .addAll(
                exclude.stream()
                    .map(e -> ImmutableList.of("match", e, "wholename"))
                    .collect(Collectors.toList()))
            .build());
  }

  /**
   * Factory method for creating {@link WatchmanGlobber} instances.
   *
   * @param basePath The base path relative to which paths matching glob patterns will be resolved.
   */
  public static WatchmanGlobber create(
      WatchmanClient watchmanClient,
      SyncCookieState syncCookieState,
      String basePath,
      String watchmanWatchRoot) {
    return new WatchmanGlobber(watchmanClient, basePath, watchmanWatchRoot, syncCookieState);
  }
}
