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

import static com.facebook.buck.apple.project_generator.ProjectGeneratorTestUtils.createDescriptionArgWithDefaults;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.junit.Test;

import java.util.Optional;

public class AppleBuildRulesTest {

  @Test
  public void testAppleLibraryIsXcodeTargetBuildRuleType() throws Exception {
    assertTrue(
        AppleBuildRules.isXcodeTargetBuildRuleType(
            Description.getBuildRuleType(AppleLibraryDescription.class)));
  }

  @Test
  public void testIosResourceIsNotXcodeTargetBuildRuleType() throws Exception {
    assertFalse(
        AppleBuildRules.isXcodeTargetBuildRuleType(
            Description.getBuildRuleType(AppleResourceDescription.class)));
  }

  @Test
  public void testAppleTestIsXcodeTargetTestBuildRuleType() throws Exception {
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo:xctest#iphoneos-i386");
    BuildTarget sandboxTarget =
        BuildTargetFactory.newInstance("//foo:xctest#iphoneos-i386,sandbox");
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(new AppleTestBuilder(sandboxTarget).build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    AppleTestBuilder appleTestBuilder = new AppleTestBuilder(
        target)
        .setContacts(ImmutableSortedSet.of())
        .setLabels(ImmutableSortedSet.of())
        .setDeps(ImmutableSortedSet.of());

    TargetNode<?, ?> appleTestNode = appleTestBuilder.build();
    BuildRule testRule = appleTestBuilder.build(
        resolver,
        new FakeProjectFilesystem(),
        TargetGraphFactory.newInstance(ImmutableSet.of(appleTestNode)));
    assertTrue(AppleBuildRules.isXcodeTargetTestBuildRule(testRule));
  }

  @Test
  public void testAppleLibraryIsNotXcodeTargetTestBuildRuleType() throws Exception {
    BuildRuleParams params = new FakeBuildRuleParamsBuilder("//foo:lib").build();
    AppleLibraryDescription.Arg arg =
        createDescriptionArgWithDefaults(FakeAppleRuleDescriptions.LIBRARY_DESCRIPTION);
    BuildRule libraryRule = FakeAppleRuleDescriptions
        .LIBRARY_DESCRIPTION
        .createBuildRule(
            TargetGraph.EMPTY,
            params,
            new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()),
            arg);

    assertFalse(AppleBuildRules.isXcodeTargetTestBuildRule(libraryRule));
  }

  @Test
  public void testXctestIsTestBundleExtension() throws Exception {
    assertTrue(AppleBuildRules.isXcodeTargetTestBundleExtension(AppleBundleExtension.XCTEST));
  }

  @Test
  public void testRecursiveTargetsIncludesBundleBinaryFromOutsideBundle() throws Exception {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//foo:lib");
    TargetNode<?, ?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .build();

    BuildTarget bundleTarget = BuildTargetFactory.newInstance("//foo:bundle");
    TargetNode<?, ?> bundleNode = AppleBundleBuilder
        .createBuilder(bundleTarget)
        .setExtension(Either.ofLeft(AppleBundleExtension.XCTEST))
        .setBinary(libraryTarget)
        .build();

    BuildTarget rootTarget = BuildTargetFactory.newInstance("//foo:root");
    TargetNode<?, ?> rootNode = AppleLibraryBuilder
        .createBuilder(rootTarget)
        .setDeps(ImmutableSortedSet.of(libraryTarget, bundleTarget))
        .build();

    Iterable<TargetNode<?, ?>> rules = AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
        TargetGraphFactory.newInstance(ImmutableSet.of(libraryNode, bundleNode, rootNode)),
        AppleBuildRules.RecursiveDependenciesMode.BUILDING,
        rootNode,
        Optional.empty());

    assertTrue(Iterables.elementsEqual(ImmutableSortedSet.of(libraryNode, bundleNode), rules));
  }

  @Test
  public void exportedDepsOfDylibsAreCollectedForLinking() throws Exception {
    BuildTarget fooLibTarget =
        BuildTargetFactory.newInstance("//foo:lib#" + CxxDescriptionEnhancer.SHARED_FLAVOR);
    TargetNode<?, ?> fooLibNode = AppleLibraryBuilder
        .createBuilder(fooLibTarget)
        .build();

    BuildTarget fooFrameworkTarget = BuildTargetFactory.newInstance("//foo:framework");
    TargetNode<?, ?> fooFrameworkNode = AppleBundleBuilder
        .createBuilder(fooFrameworkTarget)
        .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
        .setBinary(fooLibTarget)
        .build();

    BuildTarget barLibTarget =
        BuildTargetFactory.newInstance("//bar:lib#" + CxxDescriptionEnhancer.SHARED_FLAVOR);
    TargetNode<?, ?> barLibNode = AppleLibraryBuilder
        .createBuilder(barLibTarget)
        .setDeps(ImmutableSortedSet.of(fooFrameworkTarget))
        .setExportedDeps(ImmutableSortedSet.of(fooFrameworkTarget))
        .build();

    BuildTarget barFrameworkTarget = BuildTargetFactory.newInstance("//bar:framework");
    TargetNode<?, ?> barFrameworkNode = AppleBundleBuilder
        .createBuilder(barFrameworkTarget)
        .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
        .setBinary(barLibTarget)
        .build();

    BuildTarget rootTarget = BuildTargetFactory.newInstance("//foo:root");
    TargetNode<?, ?> rootNode = AppleLibraryBuilder
        .createBuilder(rootTarget)
        .setDeps(ImmutableSortedSet.of(barFrameworkTarget))
        .build();

    Iterable<TargetNode<?, ?>> rules = AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
        TargetGraphFactory.newInstance(
            ImmutableSet.of(
                rootNode,
                fooLibNode,
                fooFrameworkNode,
                barLibNode,
                barFrameworkNode)),
        AppleBuildRules.RecursiveDependenciesMode.LINKING,
        rootNode,
        Optional.empty());

    assertEquals(
        ImmutableSortedSet.of(
            barFrameworkNode,
            fooFrameworkNode),
        ImmutableSortedSet.copyOf(rules));
  }

  @Test
  public void exportedDepsAreCollectedForCopying() throws Exception {
    BuildTarget fooLibTarget =
        BuildTargetFactory.newInstance("//foo:lib#" + CxxDescriptionEnhancer.SHARED_FLAVOR);
    TargetNode<?, ?> fooLibNode = AppleLibraryBuilder
        .createBuilder(fooLibTarget)
        .build();

    BuildTarget fooFrameworkTarget = BuildTargetFactory.newInstance("//foo:framework");
    TargetNode<?, ?> fooFrameworkNode = AppleBundleBuilder
        .createBuilder(fooFrameworkTarget)
        .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
        .setBinary(fooLibTarget)
        .build();

    BuildTarget barLibTarget =
        BuildTargetFactory.newInstance("//bar:lib#" + CxxDescriptionEnhancer.SHARED_FLAVOR);
    TargetNode<?, ?> barLibNode = AppleLibraryBuilder
        .createBuilder(barLibTarget)
        .setDeps(ImmutableSortedSet.of(fooFrameworkTarget))
        .setExportedDeps(ImmutableSortedSet.of(fooFrameworkTarget))
        .build();

    BuildTarget barFrameworkTarget = BuildTargetFactory.newInstance("//bar:framework");
    TargetNode<?, ?> barFrameworkNode = AppleBundleBuilder
        .createBuilder(barFrameworkTarget)
        .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
        .setBinary(barLibTarget)
        .build();

    BuildTarget bazLibTarget =
        BuildTargetFactory.newInstance("//baz:lib#" + CxxDescriptionEnhancer.SHARED_FLAVOR);
    TargetNode<?, ?> bazLibNode = AppleLibraryBuilder
        .createBuilder(bazLibTarget)
        .setDeps(ImmutableSortedSet.of(barFrameworkTarget))
        .build();

    BuildTarget bazFrameworkTarget = BuildTargetFactory.newInstance("//baz:framework");
    TargetNode<?, ?> bazFrameworkNode = AppleBundleBuilder
        .createBuilder(bazFrameworkTarget)
        .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
        .setBinary(bazLibTarget)
        .build();

    ImmutableSet<TargetNode<?, ?>> targetNodes =
        ImmutableSet.<TargetNode<?, ?>>builder()
          .add(
            fooLibNode,
            fooFrameworkNode,
            barLibNode,
            barFrameworkNode,
            bazLibNode,
            bazFrameworkNode)
          .build();

    Iterable<TargetNode<?, ?>> rules = AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
        TargetGraphFactory.newInstance(targetNodes),
        AppleBuildRules.RecursiveDependenciesMode.COPYING,
        bazFrameworkNode,
        Optional.empty());

    assertEquals(
        ImmutableSortedSet.of(
            barFrameworkNode,
            fooFrameworkNode),
        ImmutableSortedSet.copyOf(rules));
  }

  @Test
  public void linkingStopsAtGenruleDep() throws Exception {
    // Pass a random static lib in a genrule and make sure a framework
    // depending on the genrule doesn't link against or copy in the static lib.
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo:lib");
    TargetNode<?, ?> fooLibNode = AppleLibraryBuilder
        .createBuilder(fooLibTarget)
        .build();

    BuildTarget fooGenruleTarget = BuildTargetFactory.newInstance("//foo:genrule");
    TargetNode<?, ?> fooGenruleNode = GenruleBuilder
        .newGenruleBuilder(fooGenruleTarget)
        .setOut("foo")
        .setCmd("echo hi > $OUT")
        .setSrcs(ImmutableList.of(new BuildTargetSourcePath(fooLibTarget)))
        .build();

    BuildTarget barLibTarget =
        BuildTargetFactory.newInstance("//bar:lib#" + CxxDescriptionEnhancer.SHARED_FLAVOR);
    TargetNode<?, ?> barLibNode = AppleLibraryBuilder
        .createBuilder(barLibTarget)
        .setDeps(ImmutableSortedSet.of(fooGenruleTarget))
        .build();
    BuildTarget barFrameworkTarget = BuildTargetFactory.newInstance("//bar:framework");
    TargetNode<?, ?> barFrameworkNode = AppleBundleBuilder
        .createBuilder(barFrameworkTarget)
        .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
        .setBinary(barLibTarget)
        .build();

    ImmutableSet<TargetNode<?, ?>> targetNodes =
        ImmutableSet.<TargetNode<?, ?>>builder()
          .add(
            fooLibNode,
            fooGenruleNode,
            barLibNode,
            barFrameworkNode)
          .build();

    Iterable<TargetNode<?, ?>> rules = AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
        TargetGraphFactory.newInstance(targetNodes),
        AppleBuildRules.RecursiveDependenciesMode.LINKING,
        barFrameworkNode,
        Optional.empty());

    assertEquals(
        ImmutableSortedSet.of(fooGenruleNode),
        ImmutableSortedSet.copyOf(rules));
  }

  @Test
  public void copyingStopsAtGenruleDep() throws Exception {
    // Pass a random static lib in a genrule and make sure a framework
    // depending on the genrule doesn't link against or copy in the static lib.
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo:lib");
    TargetNode<?, ?> fooLibNode = AppleLibraryBuilder
        .createBuilder(fooLibTarget)
        .build();

    BuildTarget fooGenruleTarget = BuildTargetFactory.newInstance("//foo:genrule");
    TargetNode<?, ?> fooGenruleNode = GenruleBuilder
        .newGenruleBuilder(fooGenruleTarget)
        .setOut("foo")
        .setCmd("echo hi > $OUT")
        .setSrcs(ImmutableList.of(new BuildTargetSourcePath(fooLibTarget)))
        .build();

    BuildTarget barLibTarget =
        BuildTargetFactory.newInstance("//bar:lib#" + CxxDescriptionEnhancer.SHARED_FLAVOR);
    TargetNode<?, ?> barLibNode = AppleLibraryBuilder
        .createBuilder(barLibTarget)
        .setDeps(ImmutableSortedSet.of(fooGenruleTarget))
        .build();
    BuildTarget barFrameworkTarget = BuildTargetFactory.newInstance("//bar:framework");
    TargetNode<?, ?> barFrameworkNode = AppleBundleBuilder
        .createBuilder(barFrameworkTarget)
        .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
        .setBinary(barLibTarget)
        .build();

    ImmutableSet<TargetNode<?, ?>> targetNodes =
        ImmutableSet.<TargetNode<?, ?>>builder()
          .add(
            fooLibNode,
            fooGenruleNode,
            barLibNode,
            barFrameworkNode)
          .build();

    Iterable<TargetNode<?, ?>> rules = AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
        TargetGraphFactory.newInstance(targetNodes),
        AppleBuildRules.RecursiveDependenciesMode.COPYING,
        barFrameworkNode,
        Optional.empty());

    assertEquals(
        ImmutableSortedSet.of(fooGenruleNode),
        ImmutableSortedSet.copyOf(rules));
  }

  @Test
  public void buildingStopsAtGenruleDepButNotAtBundleDep() throws Exception {
    // Pass a random static lib in a genrule and make sure a framework
    // depending on the genrule doesn't build the dependencies of that genrule.
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo:lib");
    TargetNode<?, ?> fooLibNode = AppleLibraryBuilder
        .createBuilder(fooLibTarget)
        .build();

    BuildTarget fooGenruleTarget = BuildTargetFactory.newInstance("//foo:genrule");
    TargetNode<?, ?> fooGenruleNode = GenruleBuilder
        .newGenruleBuilder(fooGenruleTarget)
        .setOut("foo")
        .setCmd("echo hi > $OUT")
        .setSrcs(ImmutableList.of(new BuildTargetSourcePath(fooLibTarget)))
        .build();

    BuildTarget barLibTarget =
        BuildTargetFactory.newInstance("//bar:lib#" + CxxDescriptionEnhancer.SHARED_FLAVOR);
    TargetNode<?, ?> barLibNode = AppleLibraryBuilder
        .createBuilder(barLibTarget)
        .setDeps(ImmutableSortedSet.of(fooGenruleTarget))
        .build();
    BuildTarget barFrameworkTarget = BuildTargetFactory.newInstance("//bar:framework");
    TargetNode<?, ?> barFrameworkNode = AppleBundleBuilder
        .createBuilder(barFrameworkTarget)
        .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
        .setBinary(barLibTarget)
        .build();

    BuildTarget bazLibTarget =
        BuildTargetFactory.newInstance("//baz:lib#" + CxxDescriptionEnhancer.SHARED_FLAVOR);
    TargetNode<?, ?> bazLibNode = AppleLibraryBuilder
        .createBuilder(bazLibTarget)
        .setDeps(ImmutableSortedSet.of(barFrameworkTarget))
        .build();
    BuildTarget bazFrameworkTarget = BuildTargetFactory.newInstance("//baz:framework");
    TargetNode<?, ?> bazFrameworkNode = AppleBundleBuilder
        .createBuilder(bazFrameworkTarget)
        .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
        .setBinary(bazLibTarget)
        .build();

    ImmutableSet<TargetNode<?, ?>> targetNodes =
        ImmutableSet.<TargetNode<?, ?>>builder()
            .add(
                fooLibNode,
                fooGenruleNode,
                barLibNode,
                barFrameworkNode,
                bazLibNode,
                bazFrameworkNode)
            .build();

    Iterable<TargetNode<?, ?>> rules = AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
        TargetGraphFactory.newInstance(targetNodes),
        AppleBuildRules.RecursiveDependenciesMode.BUILDING,
        bazFrameworkNode,
        Optional.empty());

    assertEquals(
        ImmutableSortedSet.of(barFrameworkNode, fooGenruleNode),
        ImmutableSortedSet.copyOf(rules));
  }
}
