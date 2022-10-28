/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.android.resources.filter;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FilteringPredicate {

  /** Utility class: do not instantiate. */
  private FilteringPredicate() {}

  @VisibleForTesting
  public static final Pattern NON_ENGLISH_STRINGS_FILE_PATH =
      Pattern.compile("\\b|.*/res/values-([a-z]{2})(?:-r([A-Z]{2}))*/.*.xml");

  public static final String DEFAULT_STRINGS_FILE_NAME = "strings.xml";

  public static Predicate<Path> getFilteringPredicate(
      AbsPath projectRoot,
      DirectoryStream.Filter<? super Path> ignoreFilter,
      ImmutableBiMap<Path, Path> inResDirToOutResDirMap,
      boolean filterByDensity,
      Set<ResourceFilters.Density> targetDensities,
      boolean canDownscale,
      ImmutableSet<String> locales,
      ImmutableSet<String> packagedLocales,
      boolean enableStringWhitelisting,
      ImmutableSet<Path> whitelistedStringDirs)
      throws IOException {
    List<Predicate<Path>> pathPredicates = new ArrayList<>();

    if (filterByDensity) {
      Objects.requireNonNull(targetDensities);
      Set<Path> rootResourceDirs = inResDirToOutResDirMap.keySet();

      pathPredicates.add(ResourceFilters.createDensityFilter(projectRoot, targetDensities));

      Set<Path> drawables =
          DrawableFinder.findDrawables(projectRoot, rootResourceDirs, ignoreFilter);
      pathPredicates.add(
          ResourceFilters.createImageDensityFilter(drawables, targetDensities, canDownscale));
    }

    boolean localeFilterEnabled = !locales.isEmpty();
    if (localeFilterEnabled || enableStringWhitelisting) {
      pathPredicates.add(
          path -> {
            String filePath = PathFormatter.pathWithUnixSeparators(path);
            Matcher matcher = NON_ENGLISH_STRINGS_FILE_PATH.matcher(filePath);
            if (!matcher.matches() || !filePath.endsWith(DEFAULT_STRINGS_FILE_NAME)) {
              return true;
            }
            String locale = matcher.group(1);
            if (matcher.group(2) != null) {
              locale += "_" + matcher.group(2);
            }
            if (enableStringWhitelisting) {
              return isPathWhitelisted(path, locale, packagedLocales, whitelistedStringDirs);
            } else {
              return locales.contains(locale);
            }
          });
    }
    return pathPredicates.stream().reduce(p -> true, Predicate::and);
  }

  public static Predicate<Path> getAabLanguagePackPredicate() {
    List<Predicate<Path>> pathPredicates = new ArrayList<>();
    pathPredicates.add(
        path -> {
          String filePath = PathFormatter.pathWithUnixSeparators(path);
          Matcher matcher = NON_ENGLISH_STRINGS_FILE_PATH.matcher(filePath);
          return matcher.matches() && filePath.endsWith(DEFAULT_STRINGS_FILE_NAME);
        });
    return pathPredicates.stream().reduce(p -> true, Predicate::and);
  }

  private static boolean isPathWhitelisted(
      Path path,
      String locale,
      ImmutableSet<String> packagedLocales,
      ImmutableSet<Path> whitelistedStringDirs) {
    if (packagedLocales.contains(locale)) {
      return true;
    }
    for (Path whitelistedStringDir : whitelistedStringDirs) {
      if (path.startsWith(whitelistedStringDir)) {
        return true;
      }
    }

    return false;
  }
}
