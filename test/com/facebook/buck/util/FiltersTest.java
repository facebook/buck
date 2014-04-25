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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.Filters.Density;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class FiltersTest {

  // These simulate drawables with no other qualifiers than their dpi.
  private static final String DRAWABLE = "res/drawable/";
  private static final String LDPI = "res/drawable-ldpi/";
  private static final String MDPI = "res/drawable-mdpi/";
  private static final String TVDPI = "res/drawable-tvdpi/";
  private static final String HDPI = "res/drawable-hdpi/";
  private static final String XHDPI = "res/drawable-xhdpi/";
  private static final String XXHDPI = "res/drawable-xxhdpi/";
  private static final String XXXHDPI = "res/drawable-xxxhdpi/";
  // a DPI that we don't explicitly look at
  private static final String HDPI_11 = "res/drawable-hdpi-v11/";

  // This is a drawable that's only used on XHDPI screens in Romanian.
  // We're making sure this never gets removed - could be language specific, so e.g. a drawable-mdpi
  // image won't be able to replace a missing drawable-ro-mdpi.
  private static final String XHDPI_RO = "res/drawable-ro-xhdpi/";

  // We're using these to make sure the French locale drawables are removed and kept independently
  // of the generic ones. e.g. if we have (drawable-mdpi, drawable-hdpi, drawable-fr-mdpi,
  // drawable-fr-xhdpi), and we're targeting an MDPI screen, we want to end up with
  // drawable-mdpi (M) and drawable-fr-mdpi (FM) -- the other two are removed.
  private static final String LDPI_FR = "res/drawable-fr-ldpi/";
  private static final String MDPI_FR = "res/drawable-fr-mdpi/";
  private static final String TVDPI_FR = "res/drawable-fr-tvdpi/";
  private static final String HDPI_FR = "res/drawable-fr-hdpi/";
  private static final String XHDPI_FR = "res/drawable-fr-xhdpi/";
  private static final String XXHDPI_FR = "res/drawable-fr-xxhdpi/";
  private static final String XXXHDPI_FR = "res/drawable-fr-xxxhdpi/";

  private Set<Path> candidates;

  /**
   * Create set of candidates.
   * <p>
   * For example, {@code paths("hx.png", HDPI, XHDPI)} will produce
   * {@code [res/drawable-hdpi/hx.png, res/drawable-xhdpi/hx.png]}.
   */
  @Before
  public void setUp() {
    candidates = ImmutableSet.<Path>builder()
        .addAll(paths("dmhx.png", DRAWABLE, MDPI, HDPI, XHDPI))
        .addAll(paths("dmh.png", DRAWABLE, MDPI, HDPI))
        .addAll(paths("dmx.png", DRAWABLE, MDPI, XHDPI))
        .addAll(paths("dhx.png", DRAWABLE, HDPI, XHDPI))
        .addAll(paths("hx.png", HDPI, XHDPI))
        .addAll(paths("md.png", MDPI, DRAWABLE))
        .addAll(paths("l.png", LDPI))
        .addAll(paths("d.png", DRAWABLE))
        .addAll(paths("h11.png", HDPI_11))
        .addAll(paths("2x.png", XXHDPI))
        .addAll(paths("x2x.png", XHDPI, XXHDPI))
        .addAll(paths("h3x.png", HDPI, XXXHDPI))
        .addAll(paths("dlmhx2x.png", DRAWABLE, LDPI, MDPI, HDPI, XHDPI, XXHDPI))
        .addAll(paths("dmhx_rx.png", DRAWABLE, MDPI, HDPI, XHDPI, XHDPI_RO))
        .addAll(paths("dmhx_fmhx.png", DRAWABLE, MDPI, HDPI, XHDPI, MDPI_FR, HDPI_FR, XHDPI_FR))
        .addAll(paths("lh2x_flh2x.png", LDPI, HDPI, XXHDPI, LDPI_FR, HDPI_FR, XXHDPI_FR))
        .addAll(paths("mth11_flth.png", MDPI, TVDPI, HDPI_11, LDPI_FR, TVDPI_FR, HDPI_FR))
        .addAll(paths("fm3x.png", MDPI_FR, XXXHDPI_FR))
        .addAll(paths("lx.png", LDPI, XHDPI))
        .addAll(paths("nine.9.png", LDPI, XHDPI, XXHDPI))
        .build();
  }

  /**
   * Append {@code name} to all strings in {@code options} and return as an {@link ImmutableList}.
   * @param name resource name
   * @param options of the form e.g. {@code "res/drawable-mdpi/"}
   * @return list of strings
   */
  private static List<Path> paths(String name, String... options) {
    ImmutableList.Builder<Path> builder = ImmutableList.builder();
    for (String option : options) {
      builder.add(Paths.get(option + name));
    }
    return builder.build();
  }

  /**
   * @return {@code allPaths} minus {@pathsToKeep}
   */
  @SafeVarargs
  private final Set<Path> pathsToRemove(Collection<Path> allPaths,
      Collection<Path>... pathsToKeep) {
    Set<Path> pathsToRemove = Sets.newHashSet(allPaths);
    for (Path path : Iterables.concat(pathsToKeep)) {
      pathsToRemove.remove(path);
    }
    Set<Path> result = Sets.newHashSet();
    for (Path pathToRemove : pathsToRemove) {
      result.add(pathToRemove);
    }
    return result;
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testMdpiFilterRemovesUnneededResources() {
    Set<Path> mdpi = Filters.filterByDensity(candidates, ImmutableSet.of(Density.MDPI), false);
    Iterable<Path> keepPaths = Iterables.concat(
        paths("dmhx.png", MDPI),
        paths("dmh.png", MDPI),
        paths("dmx.png", MDPI),
        paths("dhx.png", DRAWABLE),
        paths("hx.png", HDPI),
        paths("md.png", MDPI),
        paths("l.png", LDPI),
        paths("d.png", DRAWABLE),
        paths("h11.png", HDPI_11),
        paths("2x.png", XXHDPI),
        paths("x2x.png", XHDPI),
        paths("h3x.png", HDPI),
        paths("dlmhx2x.png", MDPI),
        paths("dmhx_rx.png", MDPI, XHDPI_RO),
        paths("dmhx_fmhx.png", MDPI, MDPI_FR),
        paths("lh2x_flh2x.png", HDPI, HDPI_FR),
        paths("mth11_flth.png", MDPI, HDPI_11, TVDPI_FR),
        paths("fm3x.png", MDPI_FR),
        paths("lx.png", XHDPI),
        paths("nine.9.png", XHDPI));
    MoreAsserts.assertSetEquals(pathsToRemove(candidates, Lists.newArrayList(keepPaths)), mdpi);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testLdpiMdpiFilterRemovesUnneededResources() {
    Set<Path> lmdpi =
        Filters.filterByDensity(candidates, ImmutableSet.of(Density.LDPI, Density.MDPI), false);
    Iterable<Path> keepPaths = Iterables.concat(
        paths("dmhx.png", MDPI),
        paths("dmh.png", MDPI),
        paths("dmx.png", MDPI),
        paths("dhx.png", DRAWABLE),
        paths("hx.png", HDPI),
        paths("md.png", MDPI),
        paths("l.png", LDPI),
        paths("d.png", DRAWABLE),
        paths("h11.png", HDPI_11),
        paths("2x.png", XXHDPI),
        paths("x2x.png", XHDPI),
        paths("h3x.png", HDPI),
        paths("dlmhx2x.png", LDPI, MDPI),
        paths("dmhx_rx.png", MDPI, XHDPI_RO),
        paths("dmhx_fmhx.png", MDPI, MDPI_FR),
        paths("lh2x_flh2x.png", LDPI, HDPI, LDPI_FR, HDPI_FR),
        paths("mth11_flth.png", MDPI, HDPI_11, LDPI_FR, TVDPI_FR),
        paths("fm3x.png", MDPI_FR),
        paths("lx.png", LDPI, XHDPI),
        paths("nine.9.png", LDPI, XHDPI));
    MoreAsserts.assertSetEquals(pathsToRemove(candidates, Lists.newArrayList(keepPaths)), lmdpi);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testLdpiMdpiFilterWithDownscale() {
    Set<Path> lmdpi =
        Filters.filterByDensity(candidates, ImmutableSet.of(Density.LDPI, Density.MDPI), true);
    Iterable<Path> keepPaths = Iterables.concat(
        paths("dmhx.png", MDPI),
        paths("dmh.png", MDPI),
        paths("dmx.png", MDPI),
        paths("dhx.png", DRAWABLE),
        paths("hx.png", XHDPI), // Downscale XHDPI
        paths("md.png", MDPI),
        paths("l.png", LDPI),
        paths("d.png", DRAWABLE),
        paths("h11.png", HDPI_11),
        paths("2x.png", XXHDPI),
        paths("x2x.png", XXHDPI),
        paths("h3x.png", XXXHDPI), // Downscale XXXHDPI
        paths("dlmhx2x.png", LDPI, MDPI),
        paths("dmhx_rx.png", MDPI, XHDPI_RO),
        paths("dmhx_fmhx.png", MDPI, MDPI_FR),
        paths("lh2x_flh2x.png", LDPI, XXHDPI, LDPI_FR, XXHDPI_FR), // Downscale XXHDPI
        paths("mth11_flth.png", MDPI, HDPI_11, LDPI_FR, HDPI_FR),
        paths("fm3x.png", MDPI_FR),
        paths("lx.png", LDPI, XHDPI),
        paths("nine.9.png", LDPI, XHDPI) /* No downscale */);
    MoreAsserts.assertSetEquals(pathsToRemove(candidates, Lists.newArrayList(keepPaths)), lmdpi);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testHdpiFilterRemovesUnneededResources() {
    Set<Path> hdpi = Filters.filterByDensity(candidates, ImmutableSet.of(Density.HDPI), false);
    Iterable<Path> keepPaths = Iterables.concat(
        paths("dmhx.png", HDPI),
        paths("dmh.png", HDPI),
        paths("dmx.png", XHDPI),
        paths("dhx.png", HDPI),
        paths("hx.png", HDPI),
        paths("md.png", MDPI), // drawable-mdpi preferred over drawable.
        paths("l.png", LDPI),
        paths("d.png", DRAWABLE),
        paths("h11.png", HDPI_11),
        paths("2x.png", XXHDPI),
        paths("x2x.png", XHDPI),
        paths("h3x.png", HDPI),
        paths("dlmhx2x.png", HDPI),
        paths("dmhx_rx.png", HDPI, XHDPI_RO),
        paths("dmhx_fmhx.png", HDPI, HDPI_FR),
        paths("lh2x_flh2x.png", HDPI, HDPI_FR),
        paths("mth11_flth.png", TVDPI, HDPI_11, HDPI_FR),
        paths("fm3x.png", XXXHDPI_FR),
        paths("lx.png", XHDPI),
        paths("nine.9.png", XHDPI));
    MoreAsserts.assertSetEquals(pathsToRemove(candidates, Lists.newArrayList(keepPaths)), hdpi);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testXhdpiFilterRemovesUnneededResources() {
    Set<Path> xhdpi = Filters.filterByDensity(candidates, ImmutableSet.of(Density.XHDPI), false);
    Iterable<Path> keepPaths = Iterables.concat(
        paths("dmhx.png", XHDPI),
        paths("dmh.png", HDPI),
        paths("dmx.png", XHDPI),
        paths("dhx.png", XHDPI),
        paths("hx.png", XHDPI),
        paths("md.png", MDPI), // drawable-mdpi preferred over drawable.
        paths("l.png", LDPI),
        paths("d.png", DRAWABLE),
        paths("h11.png", HDPI_11),
        paths("2x.png", XXHDPI),
        paths("x2x.png", XHDPI),
        paths("h3x.png", XXXHDPI),
        paths("dlmhx2x.png", XHDPI),
        paths("dmhx_rx.png", XHDPI, XHDPI_RO),
        paths("dmhx_fmhx.png", XHDPI, XHDPI_FR),
        paths("lh2x_flh2x.png", XXHDPI, XXHDPI_FR),
        paths("mth11_flth.png", TVDPI, HDPI_11, HDPI_FR),
        paths("fm3x.png", XXXHDPI_FR),
        paths("lx.png", XHDPI),
        paths("nine.9.png", XHDPI));
    MoreAsserts.assertSetEquals(pathsToRemove(candidates, Lists.newArrayList(keepPaths)), xhdpi);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testXxhdpiFilterRemovesUnneededResources() {
    Set<Path> xxhdpi = Filters.filterByDensity(candidates, ImmutableSet.of(Density.XXHDPI), false);
    Iterable<Path> keepPaths = Iterables.concat(
        paths("dmhx.png", XHDPI),
        paths("dmh.png", HDPI),
        paths("dmx.png", XHDPI),
        paths("dhx.png", XHDPI),
        paths("hx.png", XHDPI),
        paths("md.png", MDPI), // drawable-mdpi preferred over drawable.
        paths("l.png", LDPI),
        paths("d.png", DRAWABLE),
        paths("h11.png", HDPI_11),
        paths("2x.png", XXHDPI),
        paths("x2x.png", XXHDPI),
        paths("h3x.png", XXXHDPI),
        paths("dlmhx2x.png", XXHDPI),
        paths("dmhx_rx.png", XHDPI, XHDPI_RO),
        paths("dmhx_fmhx.png", XHDPI, XHDPI_FR),
        paths("lh2x_flh2x.png", XXHDPI, XXHDPI_FR),
        paths("mth11_flth.png", TVDPI, HDPI_11, HDPI_FR),
        paths("fm3x.png", XXXHDPI_FR),
        paths("lx.png", XHDPI),
        paths("nine.9.png", XXHDPI));
    MoreAsserts.assertSetEquals(pathsToRemove(candidates, Lists.newArrayList(keepPaths)), xxhdpi);
  }

  @Test
  public void testImageDensityFilter() {
    Set<Path> filesToRemove =
        Filters.filterByDensity(candidates, ImmutableSet.of(Density.MDPI), false);
    Predicate<Path> predicate = Filters.createImageDensityFilter(
         candidates, ImmutableSet.of(Density.MDPI), false);
    assertFalse(candidates.isEmpty());
    for (Path candidate : candidates) {
      assertEquals(!filesToRemove.contains(candidate), predicate.apply(candidate));
    }
  }
}
