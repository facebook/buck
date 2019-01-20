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

package com.facebook.buck.android;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
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
import java.util.function.Predicate;

public class ResourceFilters {

  /** Utility class: do not instantiate. */
  private ResourceFilters() {}

  /**
   * The set of supported directories in resource folders. This is defined in
   * http://developer.android.com/guide/topics/resources/providing-resources.html#table1
   */
  @VisibleForTesting
  public static final ImmutableSet<String> SUPPORTED_RESOURCE_DIRECTORIES =
      ImmutableSet.of(
          "animator",
          "anim",
          "color",
          "drawable",
          "mipmap",
          "layout",
          "menu",
          "raw",
          "values",
          "xml",
          // "interpolator" is not officially documented in the above
          // link, but several support library aar files use it.
          "interpolator");

  /**
   * Represents the names and values of valid densities for resources as defined in
   * http://developer.android.com/guide/topics/resources/providing-resources.html#DensityQualifier
   */
  public enum Density {
    // Note: ordering here matters and must be increasing by number!
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
    public final ResourceFilters.Density density;
    /** e.g. "de-v11" */
    public final String others;

    /**
     * Creates a Qualfiers given the Path to a resource folder, pulls out the density filters and
     * leaves the rest.
     */
    public static Qualifiers from(Path path) {
      ResourceFilters.Density density = Density.NO_QUALIFIER;
      StringBuilder othersBuilder = new StringBuilder();
      String parts[] = path.getFileName().toString().split("-");
      Preconditions.checkState(parts.length > 0);
      Preconditions.checkState(SUPPORTED_RESOURCE_DIRECTORIES.contains(parts[0]));
      for (int i = 1; i < parts.length; i++) {
        String qualifier = parts[i];
        if (ResourceFilters.Density.isDensity(qualifier)) {
          density = Density.from(qualifier);
        } else {
          othersBuilder.append(MoreStrings.isEmpty(othersBuilder) ? "" : "-").append(qualifier);
        }
      }
      return new Qualifiers(density, othersBuilder.toString());
    }

    private Qualifiers(Density density, String others) {
      this.density = density;
      this.others = others;
    }
  }

  /**
   * Takes a list of image files (as paths), and a target density (mdpi, hdpi, xhdpi), and returns a
   * list of files which can be safely left out when building an APK for phones with that screen
   * density. That APK will run on other screens as well but look worse due to scaling.
   *
   * <p>Each combination of non-density qualifiers is processed separately. For example, if we have
   * {@code drawable-hdpi, drawable-mdpi, drawable-xhdpi, drawable-hdpi-ro}, for a target of {@code
   * mdpi}, we'll be keeping {@code drawable-mdpi, drawable-hdpi-ro}.
   *
   *
   * @param candidates list of paths to image files
   * @param targetDensities densities we want to keep
   * @param canDownscale do we have access to an image scaler
   * @return set of files to remove
   */
  @VisibleForTesting
  static ImmutableSet<Path> filterByDensity(
      Collection<Path> candidates,
      Set<ResourceFilters.Density> targetDensities,
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
      Qualifiers qualifiers = Qualifiers.from(candidate.getParent());

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
        targets =
            Sets.newHashSet(
                Iterables.transform(
                    targetDensities,
                    input -> (input == Density.MDPI) ? Density.NO_QUALIFIER : input));
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
   * Given a list of paths of available drawables, and a target screen density, returns a {@link
   * com.google.common.base.Predicate} that fails for drawables of a different density, whenever
   * they can be safely removed.
   *
   * @param candidates list of available drawables
   * @param targetDensities set of e.g. {@code "mdpi"}, {@code "ldpi"} etc.
   * @param canDownscale if no exact match is available, retain the highest quality
   * @return a predicate as above
   */
  public static Predicate<Path> createImageDensityFilter(
      Collection<Path> candidates,
      Set<ResourceFilters.Density> targetDensities,
      boolean canDownscale) {
    Set<Path> pathsToRemove = filterByDensity(candidates, targetDensities, canDownscale);
    return path -> !pathsToRemove.contains(path);
  }

  private static String getResourceType(Path resourceFolder) {
    String parts[] = resourceFolder.getFileName().toString().split("-");
    return parts[0];
  }

  private static Path getResourceFolder(Path resourceFile) {
    for (int i = 0; i < resourceFile.getNameCount(); i++) {
      Path part = resourceFile.getName(i);
      if (SUPPORTED_RESOURCE_DIRECTORIES.contains(getResourceType(part))) {
        return resourceFile.subpath(0, i + 1);
      }
    }
    throw new HumanReadableException(
        "Resource file at %s is not in a valid resource folder.  See "
            + "http://developer.android.com/guide/topics/resources/providing-resources.html#table1 "
            + "for a list of valid resource folders.",
        resourceFile);
  }

  /**
   * Given a set of target densities, returns a {@link Predicate} that fails for any non-drawable
   * resource of a different density. Special consideration exists for the default density ({@link
   * Density#NO_QUALIFIER} when the target does not exists.
   */
  public static Predicate<Path> createDensityFilter(
      ProjectFilesystem filesystem, Set<Density> targetDensities) {
    return resourceFile -> {
      Path resourceFolder = getResourceFolder(resourceFile);
      if (resourceFolder.getFileName().toString().startsWith("drawable")) {
        // Drawables are handled independently, so do not do anything with them.
        return true;
      }
      Density density = Qualifiers.from(resourceFolder).density;

      // We should include the resource in these situations:
      // * it is one of the target densities
      // * this is a "values" resource, which we include the fallback and any targets so we do not
      //   have to parse the XML to determine if there are differences.
      // * there is no resource at any one of the target densities, and this is the fallback.
      if (targetDensities.contains(density)) {
        return true;
      }

      if (density.equals(Density.NO_QUALIFIER)) {
        String resourceType = getResourceType(resourceFolder);
        return resourceType.equals("values")
            || FluentIterable.from(targetDensities)
                .anyMatch(
                    target -> {
                      Path targetResourceFile =
                          resourceFolder
                              .resolveSibling(String.format("%s-%s", resourceType, target))
                              .resolve(resourceFolder.relativize(resourceFile));
                      return !filesystem.exists(targetResourceFile);
                    });
      }
      return false;
    };
  }
}
