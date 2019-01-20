/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class FlavorsTest {

  private static final Path ROOT = Paths.get("/opt/src/buck");

  @Test(expected = IllegalStateException.class)
  public void testCheckUnflavoredRejectsFlavoredBuildTarget() {
    BuildTarget fooBarBaz =
        BuildTargetFactory.newInstance(ROOT, "//foo", "bar", InternalFlavor.of("baz"));
    fooBarBaz.assertUnflavored();
  }

  @Test
  public void propagateFlavorDomainWithSingleFlavor() {
    BuildTarget parent = BuildTargetFactory.newInstance("//:parent#flavor");
    Flavor flavor = InternalFlavor.of("flavor");
    FlavorDomain<?> domain = new FlavorDomain<>("test", ImmutableMap.of(flavor, "something"));
    BuildTarget child = BuildTargetFactory.newInstance("//:child");
    ImmutableSortedSet<BuildTarget> result =
        Flavors.propagateFlavorDomains(parent, ImmutableList.of(domain), ImmutableList.of(child));
    assertEquals(ImmutableSortedSet.of(child.withAppendedFlavors(flavor)), result);
  }

  @Test
  public void propagateFlavorDomainWithMultipleFlavors() {
    BuildTarget parent = BuildTargetFactory.newInstance("//:parent#flavor,flavor2");
    Flavor flavor = InternalFlavor.of("flavor");
    Flavor flavor2 = InternalFlavor.of("flavor2");
    FlavorDomain<?> domain =
        new FlavorDomain<>("test", ImmutableMap.of(flavor, "something", flavor2, "something2"));
    BuildTarget child = BuildTargetFactory.newInstance("//:child");
    ImmutableSortedSet<BuildTarget> result =
        Flavors.propagateFlavorDomains(parent, ImmutableList.of(domain), ImmutableList.of(child));
    assertEquals(ImmutableSortedSet.of(child.withAppendedFlavors(flavor, flavor2)), result);
  }

  @Test
  public void propagateFlavorDomainFailsIfParentHasNoFlavor() {
    BuildTarget parent = BuildTargetFactory.newInstance("//:parent");
    Flavor flavor = InternalFlavor.of("flavor");
    FlavorDomain<?> domain = new FlavorDomain<>("test", ImmutableMap.of(flavor, "something"));
    BuildTarget child = BuildTargetFactory.newInstance("//:child");
    try {
      Flavors.propagateFlavorDomains(parent, ImmutableList.of(domain), ImmutableList.of(child));
      fail("should have thrown");
    } catch (HumanReadableException e) {
      assertTrue(e.getMessage().contains("no flavor for"));
    }
  }

  @Test
  public void propagateFlavorDomainFailsIfChildAlreadyFlavored() {
    BuildTarget parent = BuildTargetFactory.newInstance("//:parent#flavor");
    Flavor flavor = InternalFlavor.of("flavor");
    FlavorDomain<?> domain = new FlavorDomain<>("test", ImmutableMap.of(flavor, "something"));
    BuildTarget child = BuildTargetFactory.newInstance("//:child#flavor");
    try {
      Flavors.propagateFlavorDomains(parent, ImmutableList.of(domain), ImmutableList.of(child));
      fail("should have thrown");
    } catch (HumanReadableException e) {
      assertTrue(e.getMessage().contains("already has flavor"));
    }
  }
}
