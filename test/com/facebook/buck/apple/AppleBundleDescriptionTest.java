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

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.cxx.FrameworkDependencies;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableSortedSet;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Test;

public class AppleBundleDescriptionTest {

  private ProjectFilesystem filesystem = new FakeProjectFilesystem();

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS);
  }

  @Test
  public void depsHaveFlavorsPropagated() {
    BuildTarget bundleTarget = BuildTargetFactory.newInstance("//bar:bundle#iphoneos-x86_64");

    BuildTarget unflavoredDep = BuildTargetFactory.newInstance("//bar:dep1");
    BuildTarget unflavoredDepAfterPropagation =
        BuildTargetFactory.newInstance("//bar:dep1#iphoneos-x86_64");

    BuildTarget flavoredDep =
        BuildTargetFactory.newInstance("//bar:dep2#iphoneos-x86_64,iphoneos-i386");

    BuildTarget flavoredDepNotInDomain = BuildTargetFactory.newInstance("//bar:dep3#otherflavor");
    BuildTarget flavoredDepNotInDomainAfterPropagation =
        BuildTargetFactory.newInstance("//bar:dep3#iphoneos-x86_64,otherflavor");

    BuildTarget watchDep = BuildTargetFactory.newInstance("//bar:watch#watch");
    BuildTarget watchDepAfterPropagation =
        BuildTargetFactory.newInstance("//bar:watch#watchos-arm64_32,watchos-armv7k");

    BuildTarget binary = BuildTargetFactory.newInstance("//bar:binary");

    AppleBundleDescription desc = FakeAppleRuleDescriptions.BUNDLE_DESCRIPTION;
    AppleBundleDescriptionArg constructorArg =
        AppleBundleDescriptionArg.builder()
            .setName("bundle")
            .setExtension(Either.ofLeft(AppleBundleExtension.BUNDLE))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .setBinary(binary)
            .setDeps(
                ImmutableSortedSet.of(
                    binary, unflavoredDep, flavoredDep, flavoredDepNotInDomain, watchDep))
            .build();

    // Now call the find deps methods and verify it returns the targets with flavors.
    ImmutableSortedSet.Builder<BuildTarget> implicitDeps = ImmutableSortedSet.naturalOrder();
    desc.findDepsForTargetFromConstructorArgs(
        bundleTarget,
        createCellRoots(filesystem),
        constructorArg,
        implicitDeps,
        ImmutableSortedSet.naturalOrder());

    assertEquals(
        ImmutableSortedSet.<BuildTarget>naturalOrder()
            .add(unflavoredDepAfterPropagation)
            .add(flavoredDep)
            .add(flavoredDepNotInDomainAfterPropagation)
            .add(watchDepAfterPropagation)
            .build(),
        implicitDeps.build());
  }

  @Test
  public void depsHaveStripAndDebugFlavorsPropagated() {
    BuildTarget bundleTargetWithStripFlavor =
        BuildTargetFactory.newInstance("//bar:bundle#iphoneos-x86_64,strip-all,dwarf-and-dsym");

    BuildTarget unflavoredDep = BuildTargetFactory.newInstance("//bar:dep1");
    BuildTarget unflavoredDepAfterPropagation =
        BuildTargetFactory.newInstance("//bar:dep1#iphoneos-x86_64,strip-all,dwarf-and-dsym");

    BuildTarget flavoredDep =
        BuildTargetFactory.newInstance("//bar:dep2#iphoneos-i386,strip-debug,dwarf");

    BuildTarget flavoredDepNotInDomain = BuildTargetFactory.newInstance("//bar:dep3#otherflavor");
    BuildTarget flavoredDepNotInDomainAfterPropagation =
        BuildTargetFactory.newInstance(
            "//bar:dep3#iphoneos-x86_64,strip-all,dwarf-and-dsym,otherflavor");

    BuildTarget stripFlavorOnly = BuildTargetFactory.newInstance("//bar:dep4#strip-debug");
    BuildTarget stripFlavorOnlyAfterPropagation =
        BuildTargetFactory.newInstance("//bar:dep4#iphoneos-x86_64,strip-debug,dwarf-and-dsym");

    BuildTarget debugFlavorOnly = BuildTargetFactory.newInstance("//bar:dep5#dwarf");
    BuildTarget debugFlavorOnlyAfterPropagation =
        BuildTargetFactory.newInstance("//bar:dep5#iphoneos-x86_64,strip-all,dwarf");

    BuildTarget binary = BuildTargetFactory.newInstance("//bar:binary");

    AppleBundleDescription desc = FakeAppleRuleDescriptions.BUNDLE_DESCRIPTION;
    AppleBundleDescriptionArg constructorArg =
        AppleBundleDescriptionArg.builder()
            .setName("bundle")
            .setExtension(Either.ofLeft(AppleBundleExtension.BUNDLE))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .setBinary(binary)
            .setDeps(
                ImmutableSortedSet.<BuildTarget>naturalOrder()
                    .add(binary)
                    .add(unflavoredDep)
                    .add(flavoredDep)
                    .add(flavoredDepNotInDomain)
                    .add(stripFlavorOnly)
                    .add(debugFlavorOnly)
                    .build())
            .build();

    // Now call the find deps methods and verify it returns the targets with flavors.
    ImmutableSortedSet.Builder<BuildTarget> implicitDeps = ImmutableSortedSet.naturalOrder();
    desc.findDepsForTargetFromConstructorArgs(
        bundleTargetWithStripFlavor,
        createCellRoots(filesystem),
        constructorArg,
        implicitDeps,
        ImmutableSortedSet.naturalOrder());

    assertEquals(
        ImmutableSortedSet.<BuildTarget>naturalOrder()
            .add(unflavoredDepAfterPropagation)
            .add(flavoredDep)
            .add(flavoredDepNotInDomainAfterPropagation)
            .add(stripFlavorOnlyAfterPropagation)
            .add(debugFlavorOnlyAfterPropagation)
            .build(),
        implicitDeps.build());
  }

  @Test
  public void depsHaveStripAndDebugFlavorsPropagatedForPlatformDeps() {
    BuildTarget bundleTargetWithStripFlavor =
        BuildTargetFactory.newInstance("//bar:bundle#iphoneos-x86_64,strip-all,dwarf-and-dsym");

    BuildTarget unflavoredDep = BuildTargetFactory.newInstance("//bar:dep1");
    BuildTarget unflavoredDepAfterPropagation =
        BuildTargetFactory.newInstance("//bar:dep1#iphoneos-x86_64,strip-all,dwarf-and-dsym");

    BuildTarget flavoredDep =
        BuildTargetFactory.newInstance("//bar:dep2#iphoneos-i386,strip-debug,dwarf");

    BuildTarget flavoredDepNotInDomain = BuildTargetFactory.newInstance("//bar:dep3#otherflavor");
    BuildTarget flavoredDepNotInDomainAfterPropagation =
        BuildTargetFactory.newInstance(
            "//bar:dep3#iphoneos-x86_64,strip-all,dwarf-and-dsym,otherflavor");

    BuildTarget stripFlavorOnly = BuildTargetFactory.newInstance("//bar:dep4#strip-debug");
    BuildTarget stripFlavorOnlyAfterPropagation =
        BuildTargetFactory.newInstance("//bar:dep4#iphoneos-x86_64,strip-debug,dwarf-and-dsym");

    BuildTarget debugFlavorOnly = BuildTargetFactory.newInstance("//bar:dep5#dwarf");
    BuildTarget debugFlavorOnlyAfterPropagation =
        BuildTargetFactory.newInstance("//bar:dep5#iphoneos-x86_64,strip-all,dwarf");

    BuildTarget binary = BuildTargetFactory.newInstance("//bar:binary");

    AppleBundleDescription desc = FakeAppleRuleDescriptions.BUNDLE_DESCRIPTION;
    AppleBundleDescriptionArg constructorArg =
        AppleBundleDescriptionArg.builder()
            .setName("bundle")
            .setExtension(Either.ofLeft(AppleBundleExtension.BUNDLE))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .setPlatformBinary(
                PatternMatchedCollection.<BuildTarget>builder()
                    .add(Pattern.compile("iphoneos-x86_64"), binary)
                    .build())
            .setDeps(
                ImmutableSortedSet.<BuildTarget>naturalOrder()
                    .add(binary)
                    .add(unflavoredDep)
                    .add(flavoredDep)
                    .add(flavoredDepNotInDomain)
                    .add(stripFlavorOnly)
                    .add(debugFlavorOnly)
                    .build())
            .build();

    // Now call the find deps methods and verify it returns the targets with flavors.
    ImmutableSortedSet.Builder<BuildTarget> implicitDeps = ImmutableSortedSet.naturalOrder();
    desc.findDepsForTargetFromConstructorArgs(
        bundleTargetWithStripFlavor,
        createCellRoots(filesystem),
        constructorArg,
        implicitDeps,
        ImmutableSortedSet.naturalOrder());

    assertEquals(
        ImmutableSortedSet.<BuildTarget>naturalOrder()
            .add(unflavoredDepAfterPropagation)
            .add(flavoredDep)
            .add(flavoredDepNotInDomainAfterPropagation)
            .add(stripFlavorOnlyAfterPropagation)
            .add(debugFlavorOnlyAfterPropagation)
            .build(),
        implicitDeps.build());
  }

  @Test
  public void metadataTraversalForFrameworkDependenciesAreTerminated() {
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:binary");
    TargetNode<?> binaryNode = new AppleBinaryBuilder(binaryTarget).build();

    BuildTarget bundleTarget = BuildTargetFactory.newInstance("//:bundle");
    TargetNode<?> bundleNode =
        new AppleBundleBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.BUNDLE))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .setBinary(binaryTarget)
            .build();

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(bundleNode, binaryNode));
    assertTrue(
        "Although querying a binary's framework dependencies should not return empty...",
        graphBuilder
            .requireMetadata(
                binaryTarget.withFlavors(
                    FakeAppleRuleDescriptions.DEFAULT_MACOSX_X86_64_PLATFORM.getFlavor()),
                FrameworkDependencies.class)
            .isPresent());
    assertFalse(
        "Querying a bundle's framework dependencies should return empty.",
        graphBuilder.requireMetadata(bundleTarget, FrameworkDependencies.class).isPresent());
  }
}
