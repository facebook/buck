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
import static org.junit.Assert.assertTrue;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.Set;

public class FiltersTest {

  // These simulate drawables with no other qualifiers than their dpi.
  private static final String DRAWABLE = "res/drawable/";
  private static final String MDPI = "res/drawable-mdpi/";
  private static final String HDPI = "res/drawable-hdpi/";
  private static final String XHDPI = "res/drawable-xhdpi/";

  // This is a drawable that's only used on XHDPI screens in Romanian.
  // We're making sure this never gets removed - could be language specific, so e.g. a drawable-mdpi
  // image won't be able to replace a missing drawable-mdpi-ro.
  private static final String XHDPI_RO = "res/drawable-xhdpi-ro/";

  // We're using these to make sure the French locale drawables are removed and kept independently
  // of the generic ones. e.g. if we have (drawable-mdpi, drawable-hdpi, drawable-mdpi-fr,
  // drawable-xhdpi-fr), and we're targetting an MDPI screen, we want to end up with
  // drawable-mdpi (M) and drawable-mdpi-fr (FM) -- the other two are removed.
  private static final String MDPI_FR = "res/drawable-mdpi-fr/";
  private static final String HDPI_FR = "res/drawable-hdpi-fr/";
  private static final String XHDPI_FR = "res/drawable-xhdpi-fr/";

  private List<String> candidates;

  /**
   * Create list of candidates.
   * <p>
   * For example, {@code path("hx", H, X)} will produce
   * {@code [r/drawable-hdpi/hx, r/drawable-xhdpi/hx]}. All the outputs from {@code path()} are
   * then concatenated to produce the list of candidate drawables.
   */
  @Before
  public void setUp() {
    candidates = ImmutableList.<String>builder()
        .addAll(path("dmhx", DRAWABLE, MDPI, HDPI, XHDPI))
        .addAll(path("dmh", DRAWABLE, MDPI, HDPI))
        .addAll(path("dmx", DRAWABLE, MDPI, XHDPI))
        .addAll(path("hx", HDPI, XHDPI))
        .addAll(path("md", MDPI, DRAWABLE))
        .addAll(path("dmhx_rx", DRAWABLE, MDPI, HDPI, XHDPI, XHDPI_RO))
        .addAll(path("dmhx_fmhx", DRAWABLE, MDPI, HDPI, XHDPI, MDPI_FR, HDPI_FR, XHDPI_FR))
        .build();
  }

  /**
   * Append {@code name} to all strings in {@code options} and return as an {@link ImmutableList}.
   * @param name resource name
   * @param options of the form
   * @return list of strings
   */
  private static List<String> path(String name, String... options) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (String option : options) {
      builder.add(option + name);
    }
    return builder.build();
  }

  /**
   * Flatten and convert several iterables containing paths into a set of absolute {@code File}
   * instances.
   */
  private static Set<File> absoluteFileSet(Iterable<String>... iterables) {
    ImmutableSet.Builder<File> builder = ImmutableSet.builder();
    for (String path : Iterables.concat(iterables)) {
      builder.add(new File(path).getAbsoluteFile());
    }
    return builder.build();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testOnlyOneImage() {
    Set<File> mdpi = Filters.onlyOneImage(candidates, "mdpi");
    Set<File> mdpiExpected = absoluteFileSet(
        path("dmhx", DRAWABLE, HDPI, XHDPI), // We only keep the drawable-mdpi.
        path("dmh", DRAWABLE, HDPI), // We only keep the drawable-mdpi.
        path("dmx", DRAWABLE, XHDPI), // We only keep the drwable-mdpi.
        path("hx", XHDPI), // drawable-hdpi closest, drawable-xhdpi removed.
        path("md", DRAWABLE), // drawable-mdpi preferred over drawable.
        path("dmhx_rx", DRAWABLE, HDPI, XHDPI), // We only keep drawable-mdpi and drawable-xhdpi-ro.
        // -fr is separate. From those only keep drawable-mdpi-fr
        path("dmhx_fmhx", DRAWABLE, HDPI, XHDPI, HDPI_FR, XHDPI_FR)
    );
    assertEquals(mdpiExpected, mdpi);

    Set<File> hdpi = Filters.onlyOneImage(candidates, "hdpi");
    Set<File> hdpiExpected = absoluteFileSet(
        path("dmhx", DRAWABLE, MDPI, XHDPI), // We only keep the drawable-hdpi.
        path("dmh", DRAWABLE, MDPI), // drawable-hdpi closest, drawable-mdpi and drawable removed.
        path("dmx", DRAWABLE, MDPI), // drawable-xhdpi best, drawable-mdpi would lose more info.
        path("hx", XHDPI), // We only keep the drawable-hdpi.
        path("md", DRAWABLE), // drawable-mdpi preferred over drawable.
        path("dmhx_rx", DRAWABLE, MDPI, XHDPI), // We only keep drawable-hdpi and drawble-xhdpi-ro.
        // -fr is separate. From those only keep drawable-hdpi-fr.
        path("dmhx_fmhx", DRAWABLE, MDPI, XHDPI, MDPI_FR, XHDPI_FR)
    );
    assertEquals(hdpiExpected, hdpi);

    Set<File> xhdpi = Filters.onlyOneImage(candidates, "xhdpi");
    Set<File> xhdpiExpected = absoluteFileSet(
        path("dmhx", DRAWABLE, MDPI, HDPI), // We only keep drawble-xhdpi.
        path("dmh", DRAWABLE, MDPI), // drawable-hdpi closest, drawable-mdpi and drawable removed.
        path("dmx", DRAWABLE, MDPI), // We only keep drawable-xhdpi.
        path("hx", HDPI), // We only keep drawable-hdpi.
        path("md", DRAWABLE), // drawable-mdpi preferred over drawable.
        path("dmhx_rx", DRAWABLE, MDPI, HDPI), // We only keep drawable-xhdpi and drawable-xhdpi-ro.
        // -fr is separate. From those only keep drawable-xhdpi-fr.
        path("dmhx_fmhx", DRAWABLE, MDPI, HDPI, MDPI_FR, HDPI_FR)
    );
    assertEquals(xhdpiExpected, xhdpi);
  }

  @Test
  public void testImageDensityFilter() {
    Set<File> removals = Filters.onlyOneImage(candidates, "mdpi");
    Predicate<File> predicate = Filters.createImageDensityFilter(candidates, "mdpi");
    assertTrue(!candidates.isEmpty());
    for (String candidate : candidates) {
      File file = new File(candidate);
      assertEquals(!removals.contains(file.getAbsoluteFile()), predicate.apply(file));
    }
  }
}
