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
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;

import java.io.File;
import java.util.Map;
import java.util.Set;

public class Filters {


  public static final ImmutableMap<String, ImmutableList<String>> ORDERING = ImmutableMap.of(
      "mdpi", ImmutableList.of("mdpi", "", "hdpi", "xhdpi"),
      "hdpi", ImmutableList.of("hdpi", "xhdpi", "mdpi", ""),
      "xhdpi", ImmutableList.of("xhdpi", "hdpi", "mdpi", "")
  );

  public static class Qualifiers {
    public final String density;
    public final String others;

    /**
     * Splits off the density qualifier from a drawable.
     * @param file path to a drawable
     */
    public Qualifiers(File file) {
      StringBuilder densityBuilder = new StringBuilder();
      StringBuilder othersBuilder = new StringBuilder();
      for (String qualifier : file.getParentFile().getName().split("-")) {
        if (qualifier.equals("drawable")) { // We're assuming they're all drawables.
          continue;
        }
        StringBuilder whichBuilder =
            ORDERING.containsKey(qualifier) ? densityBuilder : othersBuilder;
        whichBuilder.append((MoreStrings.isEmpty(whichBuilder) ? "" : "-") + qualifier);
      }
      this.density = densityBuilder.toString();
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
   * @param targetDensity density to keep
   * @return set of files to remove
   */
  @VisibleForTesting
  static Set<File> onlyOneImage(Iterable<String> candidates, String targetDensity) {
    ImmutableSet.Builder<File> removals = ImmutableSet.builder();

    Table<String, String, String> imageValues = HashBasedTable.create();

    // Create mappings for drawables. If candidate == "<base>/drawable-<dpi>-<other>/<filename>",
    // then we'll record a mapping of the form ("<base>/<filename>/<other>", "<dpi>") -> candidate.
    for (String candidate : candidates) {
      File f = new File(candidate);

      Qualifiers qualifiers = new Qualifiers(f);

      String filename = f.getName();
      String density = qualifiers.density;
      String resDirectory = f.getParentFile().getParent();
      String key = String.format("%s/%s/%s", resDirectory, filename, qualifiers.others);
      imageValues.put(key, density, candidate);
    }

    for (String key : imageValues.rowKeySet()) {
      Map<String, String> options = imageValues.row(key);
      Set<String> densitiesForKey = options.keySet();

      ImmutableList<String> resOrder = ORDERING.get(targetDensity);

      // If there's only one density, or there are densities we don't work with, skip this image.
      if (options.size() == 1 || !resOrder.containsAll(densitiesForKey)) {
        continue;
      }

      // Go through DPIs in order of preference and keep the first one actually present.
      String keep = resOrder.get(0);
      for (String res : resOrder) {
        if (densitiesForKey.contains(res)) {
          keep = res;
          break;
        }
      }

      // Mark all the others for removal.
      for (String density : densitiesForKey) {
        if (keep.equals(density)) {
          continue;
        }
        removals.add(new File(options.get(density)).getAbsoluteFile());
      }
    }
    return removals.build();
  }

  /**
   * Given a list of paths of available drawables, and a target screen density, returns a
   * {@link com.google.common.base.Predicate} that fails for drawables of a different
   * density, whenever they can be safely removed.
   * @param candidates list of available drawables
   * @param targetDensity one of {@code "xhdpi"}, {@code "hdpi"} or {@code "mdpi"}
   * @return a predicate as above
   */
  public static Predicate<File> createImageDensityFilter(Iterable<String> candidates,
      String targetDensity) {
    Preconditions.checkArgument(ORDERING.containsKey(targetDensity));
    final Set<File> removals = onlyOneImage(candidates, targetDensity);
    return new Predicate<File>() {
      @Override
      public boolean apply(File pathname) {
        return !removals.contains(pathname.getAbsoluteFile());
      }
    };
  }
}
