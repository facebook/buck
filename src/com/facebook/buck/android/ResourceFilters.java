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

import com.facebook.buck.util.MoreStrings;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ResourceFilters {

  /** Utility class: do not instantiate. */
  private ResourceFilters() {}

  /**
   * The set of supported directories in resource folders.  This is defined in
   * http://developer.android.com/guide/topics/resources/providing-resources.html#table1
   */
  @VisibleForTesting
  public static final ImmutableSet<String> SUPPORTED_RESOURCE_DIRECTORIES = ImmutableSet.of(
      "animator",
      "anim",
      "color",
      "drawable",
      "mipmap",
      "layout",
      "menu",
      "raw",
      "values",
      "xml");

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
          othersBuilder.append((MoreStrings.isEmpty(othersBuilder) ? "" : "-") + qualifier);
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
      Set<ResourceFilters.Density> targetDensities,
      boolean canDownscale) {
    final Set<Path> pathsToRemove = filterByDensity(candidates, targetDensities, canDownscale);
    return new Predicate<Path>() {
      @Override
      public boolean apply(Path path) {
        return !pathsToRemove.contains(path);
      }
    };
  }

  private static String getResourceType(Path resourceFolder) {
    String parts[] = resourceFolder.getFileName().toString().split("-");
    Preconditions.checkState(parts.length > 0);
    return parts[0];
  }

  private static Path getResourceFolder(Collection<Path> candidates, final Path resourceFile) {
    Optional<Path> match = FluentIterable.from(candidates)
        .firstMatch(new Predicate<Path>() {
          @Override
          public boolean apply(Path resourceFolder) {
            return resourceFile.startsWith(resourceFolder);
          }
        });
    Preconditions.checkState(match.isPresent());
    return match.get();
  }

  /**
   * Puts each candidates resource folder into the appropriate resource directory type.
   */
  private static ImmutableMap<String, ImmutableSet<Path>> filterFoldersIntoResourceTypes(
      Collection<Path> candidates) {
    Map<String, ImmutableSet.Builder<Path>> builders =
        new HashMap<>(SUPPORTED_RESOURCE_DIRECTORIES.size());
    for (String folderName : SUPPORTED_RESOURCE_DIRECTORIES) {
      builders.put(folderName, ImmutableSet.<Path>builder());
    }

    for (Path resourceFolder : candidates) {
      String resourceType = getResourceType(resourceFolder);
      Preconditions.checkState(
          builders.containsKey(resourceType),
          "Unsupported resource type %s when processing resource folder %s",
          resourceType,
          resourceFolder);
      builders.get(resourceType).add(resourceFolder);
    }

    ImmutableMap.Builder<String, ImmutableSet<Path>> bucketsBuilder = ImmutableMap.builder();
    for (Map.Entry<String, ImmutableSet.Builder<Path>> builderEntry : builders.entrySet()) {
      bucketsBuilder.put(builderEntry.getKey(), builderEntry.getValue().build());
    }
    return bucketsBuilder.build();
  }

  /**
   * Given a set of target densities, returns a {@link Predicate} that fails for any non-drawable
   * resource folder of a different density.
   */
  public static Predicate<Path> createDensityFilter(
      final Collection<Path> resourceFolders,
      final Set<Density> targetDensities) {
    final Supplier<ImmutableMap<String, ImmutableSet<Path>>> buckets =
        new Supplier<ImmutableMap<String, ImmutableSet<Path>>>() {
          @Override
          public ImmutableMap<String, ImmutableSet<Path>> get() {
            return filterFoldersIntoResourceTypes(resourceFolders);
          }
        };
    return new Predicate<Path>() {
      @Override
      public boolean apply(Path resourceFile) {
        Path resourceFolder = getResourceFolder(resourceFolders, resourceFile);
        String resourceType = getResourceType(resourceFolder);
        if (resourceType.equals("drawable")) {
          // Drawables are handled independently, so do not do anything with them.
          return true;
        }
        Density density = Qualifiers.from(resourceFolder).density;

        // We should include the folder in these situations:
        // * it is one of the target densities
        // * there is no folder at one of the target densities, and this is the fallback.
        if (targetDensities.contains(density)) {
          return true;
        }
        return density.equals(Density.NO_QUALIFIER) && FluentIterable
            .from(buckets.get().get(resourceType))
            .filter(new Predicate<Path>() {
              @Override
              public boolean apply(Path availableResourceFolder) {
                return targetDensities.contains(Qualifiers.from(availableResourceFolder).density);
              }
            })
            .size() < targetDensities.size();
      }
    };
  }
}
