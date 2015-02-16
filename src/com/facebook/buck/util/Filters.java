/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

public class Filters {

  /** Utility class: do not instantiate. */
  private Filters() {}

  public enum Density {
    LDPI("ldpi", 120.0),
    NO_QUALIFIER("", 160.0),
    MDPI("mdpi", 160.0),
    TVDPI("tvdpi", 213.0),
    HDPI("hdpi", 240.0),
    XHDPI("xhdpi", 320.0),
    XXHDPI("xxhdpi", 480.0),
    XXXHDPI("xxxhdpi", 640.0);

    private final String qualifier;
    private final double value;

    public static final Ordering<Density> ORDERING = Ordering.natural();

    Density(String qualifier, double value) {
      this.qualifier = qualifier;
      this.value = value;
    }

    public double value() {
      return value;
    }

    @Override
    public String toString() {
      return qualifier;
    }

    public static Density from(String s) {
      return s.isEmpty() ? NO_QUALIFIER : valueOf(s.toUpperCase());
    }

    public static boolean isDensity(String s) {
      for (Density choice : values()) {
        if (choice.toString().equals(s)) {
          return true;
        }
      }
      return false;
    }
  }

  public static class Qualifiers {
    /** e.g. "xhdpi" */
    public final Filters.Density density;
    /** e.g. "de-v11" */
    public final String others;

    /**
     * Splits off the density qualifier from a drawable.
     * @param path path to a drawable
     */
    public Qualifiers(Path path) {
      Filters.Density density = Density.NO_QUALIFIER;
      StringBuilder othersBuilder = new StringBuilder();
      for (String qualifier : path.getParent().getFileName().toString().split("-")) {
        if (qualifier.equals("drawable")) { // We're assuming they're all drawables.
          continue;
        }

        if (Filters.Density.isDensity(qualifier)) {
          density = Density.from(qualifier);
        } else {
          othersBuilder.append((MoreStrings.isEmpty(othersBuilder) ? "" : "-") + qualifier);
        }

      }
      this.density = density;
      this.others = othersBuilder.toString();
    }
  }

  /**
   * Takes a list of image files (as paths), and a target density (mdpi, hdpi, xhdpi), and
   * returns a list of files which can be safely left out when building an APK for phones with that
   * screen density. That APK will run on other screens as well but look worse due to scaling.
   * <p>
   * Each combination of non-density qualifiers is processed separately. For example, if we have
   * {@code drawable-hdpi, drawable-mdpi, drawable-xhdpi, drawable-hdpi-ro}, for a target of {@code
   * mdpi}, we'll be keeping {@code drawable-mdpi, drawable-hdpi-ro}.
   * @param candidates list of paths to image files
   * @param targetDensities densities we want to keep
   * @param canDownscale do we have access to an image scaler
   * @return set of files to remove
   */
  @VisibleForTesting
  static Set<Path> filterByDensity(
      Collection<Path> candidates,
      Set<Filters.Density> targetDensities,
      boolean canDownscale) {
    ImmutableSet.Builder<Path> removals = ImmutableSet.builder();

    Table<String, Density, Path> imageValues = HashBasedTable.create();

    // Create mappings for drawables. If candidate == "<base>/drawable-<dpi>-<other>/<filename>",
    // then we'll record a mapping of the form ("<base>/<filename>/<other>", "<dpi>") -> candidate.
    // For example:
    //                                    mdpi                               hdpi
    //                       --------------------------------------------------------------------
    // key: res/some.png/    |  res/drawable-mdpi/some.png          res/drawable-hdpi/some.png
    // key: res/some.png/fr  |  res/drawable-fr-hdpi/some.png
    for (Path candidate : candidates) {
      Qualifiers qualifiers = new Qualifiers(candidate);

      String filename = candidate.getFileName().toString();
      Density density = qualifiers.density;
      String resDirectory = candidate.getParent().getParent().toString();
      String key = String.format("%s/%s/%s", resDirectory, filename, qualifiers.others);
      imageValues.put(key, density, candidate);
    }

    for (String key : imageValues.rowKeySet()) {
      Map<Density, Path> options = imageValues.row(key);
      Set<Density> available = options.keySet();

      // This is to make sure we preserve the existing structure of drawable/ files.
      Set<Density> targets = targetDensities;
      if (available.contains(Density.NO_QUALIFIER) && !available.contains(Density.MDPI)) {
        targets = Sets.newHashSet(Iterables.transform(targetDensities,
            new Function<Density, Density>() {
              @Override
              public Density apply(Density input) {
                return (input == Density.MDPI) ? Density.NO_QUALIFIER : input;
              }
            }));
      }

      // We intend to keep all available targeted densities.
      Set<Density> toKeep = Sets.newHashSet(Sets.intersection(available, targets));

      // Make sure we have a decent fit for the largest target density.
      Density largestTarget = Density.ORDERING.max(targets);
      if (!available.contains(largestTarget)) {
        Density fallback = null;
        // Downscaling nine-patch drawables would require extra logic, not doing that yet.
        if (canDownscale && !options.values().iterator().next().toString().endsWith(".9.png")) {
          // Highest possible quality, because we'll downscale it.
          fallback = Density.ORDERING.max(available);
        } else {
          // We want to minimize size, so we'll go for the smallest available density that's
          // still larger than the missing one and, missing that, for the largest available.
          for (Density candidate : Density.ORDERING.reverse().sortedCopy(available)) {
            if (fallback == null || Density.ORDERING.compare(candidate, largestTarget) > 0) {
              fallback = candidate;
            }
          }
        }
        toKeep.add(fallback);
      }

      // Mark remaining densities for removal.
      for (Density density : Sets.difference(available, toKeep)) {
        removals.add(options.get(density));
      }
    }

    return removals.build();
  }

  /**
   * Given a list of paths of available drawables, and a target screen density, returns a
   * {@link com.google.common.base.Predicate} that fails for drawables of a different
   * density, whenever they can be safely removed.
   * @param candidates list of available drawables
   * @param targetDensities set of e.g. {@code "mdpi"}, {@code "ldpi"} etc.
   * @param canDownscale if no exact match is available, retain the highest quality
   * @return a predicate as above
   */
  public static Predicate<Path> createImageDensityFilter(
      Collection<Path> candidates,
      Set<Filters.Density> targetDensities,
      boolean canDownscale) {
    final Set<Path> pathsToRemove = filterByDensity(candidates, targetDensities, canDownscale);
    return new Predicate<Path>() {
      @Override
      public boolean apply(Path path) {
        return !pathsToRemove.contains(path);
      }
    };
  }
}
