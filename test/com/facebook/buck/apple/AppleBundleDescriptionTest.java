/*
 * Copyright 2015-present Facebook, Inc.
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

import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

public class AppleBundleDescriptionTest {

  private ProjectFilesystem filesystem = new FakeProjectFilesystem();

  @Test
  public void depsHaveFlavorsPropagated() {
    BuildTarget bundleTarget = BuildTargetFactory.newInstance("//bar:bundle#iphoneos-x86_64");

    BuildTarget unflavoredDep = BuildTargetFactory.newInstance("//bar:dep1");
    BuildTarget unflavoredDepAfterPropagation =
        BuildTargetFactory.newInstance("//bar:dep1#iphoneos-x86_64");

    BuildTarget flavoredDep = BuildTargetFactory.newInstance(
        "//bar:dep2#iphoneos-x86_64,iphoneos-i386");

    BuildTarget flavoredDepNotInDomain =
        BuildTargetFactory.newInstance("//bar:dep3#otherflavor");
    BuildTarget flavoredDepNotInDomainAfterPropagation =
        BuildTargetFactory.newInstance("//bar:dep3#iphoneos-x86_64,otherflavor");

    BuildTarget watchDep = BuildTargetFactory.newInstance(
        "//bar:watch#watch");
    BuildTarget watchDepAfterPropagation = BuildTargetFactory.newInstance(
        "//bar:watch#watchos-armv7k");

    BuildTarget binary =
        BuildTargetFactory.newInstance("//bar:binary");

    AppleBundleDescription desc = FakeAppleRuleDescriptions.BUNDLE_DESCRIPTION;
    AppleBundleDescription.Arg constructorArg = desc.createUnpopulatedConstructorArg();
    constructorArg.binary = binary;
    constructorArg.deps =
        ImmutableSortedSet.of(
            binary,
            unflavoredDep,
            flavoredDep,
            flavoredDepNotInDomain,
            watchDep);

    // Now call the find deps methods and verify it returns the targets with flavors.
    Iterable<BuildTarget> results = desc.findDepsForTargetFromConstructorArgs(
        bundleTarget,
        createCellRoots(filesystem),
        constructorArg);

    assertEquals(
        ImmutableSet.<BuildTarget>builder()
            .add(unflavoredDepAfterPropagation)
            .add(flavoredDep)
            .add(flavoredDepNotInDomainAfterPropagation)
            .add(watchDepAfterPropagation)
            .build(),
        ImmutableSet.copyOf(results));
  }

  @Test
  public void depsHaveStripAndDebugFlavorsPropagated() {
    BuildTarget bundleTargetWithStripFlavor =
        BuildTargetFactory.newInstance("//bar:bundle#iphoneos-x86_64,strip-all,dwarf-and-dsym");

    BuildTarget unflavoredDep = BuildTargetFactory.newInstance("//bar:dep1");
    BuildTarget unflavoredDepAfterPropagation =
        BuildTargetFactory.newInstance("//bar:dep1#iphoneos-x86_64,strip-all,dwarf-and-dsym");

    BuildTarget flavoredDep = BuildTargetFactory.newInstance(
        "//bar:dep2#iphoneos-i386,strip-debug,dwarf");

    BuildTarget flavoredDepNotInDomain =
        BuildTargetFactory.newInstance("//bar:dep3#otherflavor");
    BuildTarget flavoredDepNotInDomainAfterPropagation =
        BuildTargetFactory.newInstance(
            "//bar:dep3#iphoneos-x86_64,strip-all,dwarf-and-dsym,otherflavor");

    BuildTarget stripFlavorOnly =
        BuildTargetFactory.newInstance("//bar:dep4#strip-debug");
    BuildTarget stripFlavorOnlyAfterPropagation =
        BuildTargetFactory.newInstance("//bar:dep4#iphoneos-x86_64,strip-debug,dwarf-and-dsym");

    BuildTarget debugFlavorOnly =
        BuildTargetFactory.newInstance("//bar:dep5#dwarf");
    BuildTarget debugFlavorOnlyAfterPropagation =
        BuildTargetFactory.newInstance("//bar:dep5#iphoneos-x86_64,strip-all,dwarf");

    BuildTarget binary =
        BuildTargetFactory.newInstance("//bar:binary");

    AppleBundleDescription desc = FakeAppleRuleDescriptions.BUNDLE_DESCRIPTION;
    AppleBundleDescription.Arg constructorArg = desc.createUnpopulatedConstructorArg();
    constructorArg.binary = binary;
    constructorArg.deps =
        ImmutableSortedSet.<BuildTarget>naturalOrder()
            .add(binary)
            .add(unflavoredDep)
            .add(flavoredDep)
            .add(flavoredDepNotInDomain)
            .add(stripFlavorOnly)
            .add(debugFlavorOnly)
            .build();

    // Now call the find deps methods and verify it returns the targets with flavors.
    Iterable<BuildTarget> results = desc.findDepsForTargetFromConstructorArgs(
        bundleTargetWithStripFlavor,
        createCellRoots(filesystem),
        constructorArg);

    assertEquals(
        ImmutableSortedSet.<BuildTarget>naturalOrder()
            .add(unflavoredDepAfterPropagation)
            .add(flavoredDep)
            .add(flavoredDepNotInDomainAfterPropagation)
            .add(stripFlavorOnlyAfterPropagation)
            .add(debugFlavorOnlyAfterPropagation)
            .build(),
        ImmutableSet.copyOf(results));
  }
}
