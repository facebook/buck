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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class BuildTargetsTest {

  private static final Path ROOT = Paths.get("/opt/src/buck");

  @Test
  public void testCreateFlavoredBuildTarget() {
    BuildTarget fooBar = BuildTarget.builder(ROOT, "//foo", "bar").build();
    BuildTarget fooBarBaz =
        BuildTargets.createFlavoredBuildTarget(fooBar.checkUnflavored(), ImmutableFlavor.of("baz"));
    assertTrue(fooBarBaz.isFlavored());
    assertEquals("//foo:bar#baz", fooBarBaz.getFullyQualifiedName());
  }

  @Test(expected = IllegalStateException.class)
  public void testCheckUnflavoredRejectsFlavoredBuildTarget() {
    BuildTarget fooBarBaz = BuildTarget
        .builder(ROOT, "//foo", "bar")
        .addFlavors(ImmutableFlavor.of("baz"))
        .build();
    fooBarBaz.checkUnflavored();
  }

  @Test
  public void propagateFlavorDomainWithSingleFlavor() {
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
        ImmutableSortedSet.of(BuildTarget.builder(child).addFlavors(flavor).build()),
        result);
  }

  @Test
  public void propagateFlavorDomainWithMultipleFlavors() {
    BuildTarget parent = BuildTargetFactory.newInstance("//:parent#flavor,flavor2");
    Flavor flavor = ImmutableFlavor.of("flavor");
    Flavor flavor2 = ImmutableFlavor.of("flavor2");
    FlavorDomain<?> domain = new FlavorDomain<>(
        "test",
        ImmutableMap.of(flavor, "something", flavor2, "something2"));
    BuildTarget child = BuildTargetFactory.newInstance("//:child");
    ImmutableSortedSet<BuildTarget> result = BuildTargets.propagateFlavorDomains(
        parent,
        ImmutableList.<FlavorDomain<?>>of(domain),
        ImmutableList.of(child));
    assertEquals(
        ImmutableSortedSet.of(BuildTarget.builder(child)
                .addFlavors(flavor)
                .addFlavors(flavor2)
                .build()),
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

  @Test
  public void targetsWithTheSameRelativePathButNotTheSameCellMightNotBeAbleToSeeEachOther() {
    ProjectFilesystem rootOne = FakeProjectFilesystem.createJavaOnlyFilesystem("/one");
    ProjectFilesystem rootTwo = FakeProjectFilesystem.createJavaOnlyFilesystem("/two");

    BuildTarget targetOne = BuildTargetFactory.newInstance(rootOne, "//foo:bar");
    BuildTarget targetTwo = BuildTargetFactory.newInstance(rootTwo, "//foo:bar");

    BuildTargetPattern pattern = new SingletonBuildTargetPattern(
        rootOne.getRootPath(),
        "//foo:other");
    boolean isVisible = BuildTargets.isVisibleTo(targetTwo, ImmutableSet.of(pattern), targetOne);

    assertThat(isVisible, is(false));
  }
}
