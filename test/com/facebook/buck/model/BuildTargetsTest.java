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

package com.facebook.buck.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

public class BuildTargetsTest {

  @Test
  public void testCreateFlavoredBuildTarget() {
    BuildTarget fooBar = BuildTarget.builder("//foo", "bar").build();
    BuildTarget fooBarBaz =
        BuildTargets.createFlavoredBuildTarget(fooBar, ImmutableFlavor.of("baz"));
    assertTrue(fooBarBaz.isFlavored());
    assertEquals("//foo:bar#baz", fooBarBaz.getFullyQualifiedName());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testCreateFlavoredBuildTargetRejectsFlavoredBuildTarget() {
    BuildTarget fooBarBaz = BuildTarget
        .builder("//foo", "bar")
        .addFlavors(ImmutableFlavor.of("baz"))
        .build();
    BuildTargets.createFlavoredBuildTarget(fooBarBaz, ImmutableFlavor.of("buzz"));
  }

  @Test
  public void propagateFlavorDomain() {
    BuildTarget parent = BuildTargetFactory.newInstance("//:parent#flavor");
    Flavor flavor = ImmutableFlavor.of("flavor");
    FlavorDomain<?> domain = new FlavorDomain<>(
        "test",
        ImmutableMap.of(flavor, "something"));
    BuildTarget child = BuildTargetFactory.newInstance("//:child");
    ImmutableSortedSet<BuildTarget> result = BuildTargets.propagateFlavorDomains(
        parent,
        ImmutableList.<FlavorDomain<?>>of(domain),
        ImmutableList.of(child));
    assertEquals(
        ImmutableSortedSet.<BuildTarget>of(BuildTarget.builder(child).addFlavors(flavor).build()),
        result);
  }

  @Test
  public void propagateFlavorDomainFailsIfParentHasNoFlavor() {
    BuildTarget parent = BuildTargetFactory.newInstance("//:parent");
    Flavor flavor = ImmutableFlavor.of("flavor");
    FlavorDomain<?> domain = new FlavorDomain<>(
        "test",
        ImmutableMap.of(flavor, "something"));
    BuildTarget child = BuildTargetFactory.newInstance("//:child");
    try {
      BuildTargets.propagateFlavorDomains(
          parent,
          ImmutableList.<FlavorDomain<?>>of(domain),
          ImmutableList.of(child));
      fail("should have thrown");
    } catch (HumanReadableException e) {
      assertTrue(e.getMessage().contains("no flavor for"));
    }
  }

  @Test
  public void propagateFlavorDomainFailsIfChildAlreadyFlavored() {
    BuildTarget parent = BuildTargetFactory.newInstance("//:parent#flavor");
    Flavor flavor = ImmutableFlavor.of("flavor");
    FlavorDomain<?> domain = new FlavorDomain<>(
        "test",
        ImmutableMap.of(flavor, "something"));
    BuildTarget child = BuildTargetFactory.newInstance("//:child#flavor");
    try {
      BuildTargets.propagateFlavorDomains(
          parent,
          ImmutableList.<FlavorDomain<?>>of(domain),
          ImmutableList.of(child));
      fail("should have thrown");
    } catch (HumanReadableException e) {
      assertTrue(e.getMessage().contains("already has flavor"));
    }
  }

}
