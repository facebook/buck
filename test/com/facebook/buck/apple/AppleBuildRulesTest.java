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

import static com.facebook.buck.apple.AppleResources.IS_APPLE_BUNDLE_RESOURCE_NODE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TestBuildRuleCreationContextFactory;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.Collection;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class AppleBuildRulesTest {

  @Parameterized.Parameters(name = "useCache: {0}")
  public static Collection<Object[]> data() {
    return ImmutableList.of(new Object[] {false}, new Object[] {true});
  }

  @Parameterized.Parameter(0)
  public boolean useCache;

  @Before
  public void setUp() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
  }

  @Test
  public void testAppleLibraryIsXcodeTargetDescription() {
    Cell rootCell = (new TestCellBuilder()).build();
    BuildTarget libraryTarget = BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "lib");
    TargetNode<AppleLibraryDescriptionArg, ?> library =
        AppleLibraryBuilder.createBuilder(libraryTarget).setSrcs(ImmutableSortedSet.of()).build();
    assertTrue(AppleBuildRules.isXcodeTargetDescription(library.getDescription()));
  }

  @Test
  public void testIosResourceIsNotXcodeTargetDescription() {
    Cell rootCell = (new TestCellBuilder()).build();
    BuildTarget resourceTarget = BuildTargetFactory.newInstance(rootCell.getRoot(), "//foo", "res");
    TargetNode<?, ?> resourceNode =
        AppleResourceBuilder.createBuilder(resourceTarget)
            .setFiles(ImmutableSet.of())
            .setDirs(ImmutableSet.of())
            .build();
    assertFalse(AppleBuildRules.isXcodeTargetDescription(resourceNode.getDescription()));
  }

  @Test
  public void testAppleTestIsXcodeTargetTestBuildRuleType() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:xctest#iphoneos-i386");
    BuildTarget sandboxTarget =
        BuildTargetFactory.newInstance("//foo:xctest#iphoneos-i386")
            .withFlavors(CxxDescriptionEnhancer.SANDBOX_TREE_FLAVOR);
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(
                new AppleTestBuilder(sandboxTarget)
                    .setInfoPlist(FakeSourcePath.of("Info.plist"))
                    .build()));
    AppleTestBuilder appleTestBuilder =
        new AppleTestBuilder(target)
            .setContacts(ImmutableSortedSet.of())
            .setLabels(ImmutableSortedSet.of())
            .setDeps(ImmutableSortedSet.of())
            .setInfoPlist(FakeSourcePath.of("Info.plist"));

    TargetNode<?, ?> appleTestNode = appleTestBuilder.build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(ImmutableSet.of(appleTestNode));

    BuildRule testRule =
        appleTestBuilder.build(graphBuilder, new FakeProjectFilesystem(), targetGraph);
    assertTrue(AppleBuildRules.isXcodeTargetTestBuildRule(testRule));
  }

  @Test
  public void testAppleLibraryIsNotXcodeTargetTestBuildRuleType() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:lib");
    BuildRuleParams params = TestBuildRuleParams.create();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildRule libraryRule =
        FakeAppleRuleDescriptions.LIBRARY_DESCRIPTION.createBuildRule(
            TestBuildRuleCreationContextFactory.create(graphBuilder, projectFilesystem),
            buildTarget,
            params,
            AppleLibraryDescriptionArg.builder().setName("lib").build());

    assertFalse(AppleBuildRules.isXcodeTargetTestBuildRule(libraryRule));
  }

  @Test
  public void testXctestIsTestBundleExtension() {
    assertTrue(AppleBuildRules.isXcodeTargetTestBundleExtension(AppleBundleExtension.XCTEST));
  }

  @Test
  public void testRecursiveTargetsIncludesBundleBinaryFromOutsideBundle() {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//foo:lib");
    TargetNode<?, ?> libraryNode = AppleLibraryBuilder.createBuilder(libraryTarget).build();

    BuildTarget bundleTarget = BuildTargetFactory.newInstance("//foo:bundle");
    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.XCTEST))
            .setBinary(libraryTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    BuildTarget rootTarget = BuildTargetFactory.newInstance("//foo:root");
    TargetNode<?, ?> rootNode =
        AppleLibraryBuilder.createBuilder(rootTarget)
            .setDeps(ImmutableSortedSet.of(libraryTarget, bundleTarget))
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(ImmutableSet.of(libraryNode, bundleNode, rootNode));
    Optional<AppleDependenciesCache> cache =
        useCache ? Optional.of(new AppleDependenciesCache(targetGraph)) : Optional.empty();

    for (int i = 0; i < (useCache ? 2 : 1); i++) {
      Iterable<TargetNode<?, ?>> rules =
          AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
              targetGraph,
              cache,
              AppleBuildRules.RecursiveDependenciesMode.BUILDING,
              rootNode,
              Optional.empty());

      assertTrue(Iterables.elementsEqual(ImmutableSortedSet.of(libraryNode, bundleNode), rules));
    }
  }

  @Test
  public void exportedDepsOfDylibsAreCollectedForLinking() {
    BuildTarget fooLibTarget =
        BuildTargetFactory.newInstance("//foo:lib#" + CxxDescriptionEnhancer.SHARED_FLAVOR);
    TargetNode<?, ?> fooLibNode = AppleLibraryBuilder.createBuilder(fooLibTarget).build();

    BuildTarget fooFrameworkTarget = BuildTargetFactory.newInstance("//foo:framework");
    TargetNode<?, ?> fooFrameworkNode =
        AppleBundleBuilder.createBuilder(fooFrameworkTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setBinary(fooLibTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    BuildTarget barLibTarget =
        BuildTargetFactory.newInstance("//bar:lib#" + CxxDescriptionEnhancer.SHARED_FLAVOR);
    TargetNode<?, ?> barLibNode =
        AppleLibraryBuilder.createBuilder(barLibTarget)
            .setDeps(ImmutableSortedSet.of(fooFrameworkTarget))
            .setExportedDeps(ImmutableSortedSet.of(fooFrameworkTarget))
            .build();

    BuildTarget barFrameworkTarget = BuildTargetFactory.newInstance("//bar:framework");
    TargetNode<?, ?> barFrameworkNode =
        AppleBundleBuilder.createBuilder(barFrameworkTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setBinary(barLibTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    BuildTarget rootTarget = BuildTargetFactory.newInstance("//foo:root");
    TargetNode<?, ?> rootNode =
        AppleLibraryBuilder.createBuilder(rootTarget)
            .setDeps(ImmutableSortedSet.of(barFrameworkTarget))
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            ImmutableSet.of(rootNode, fooLibNode, fooFrameworkNode, barLibNode, barFrameworkNode));
    Optional<AppleDependenciesCache> cache =
        useCache ? Optional.of(new AppleDependenciesCache(targetGraph)) : Optional.empty();

    for (int i = 0; i < (useCache ? 2 : 1); i++) {
      Iterable<TargetNode<?, ?>> rules =
          AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
              targetGraph,
              cache,
              AppleBuildRules.RecursiveDependenciesMode.LINKING,
              rootNode,
              Optional.empty());

      assertEquals(
          ImmutableSortedSet.of(barFrameworkNode, fooFrameworkNode),
          ImmutableSortedSet.copyOf(rules));
    }
  }

  @Test
  public void resourceDepsOfDylibsAreNotIncludedInMainBundle() {
    BuildTarget sharedResourceTarget = BuildTargetFactory.newInstance("//shared:resource");
    TargetNode<?, ?> sharedResourceNode =
        AppleResourceBuilder.createBuilder(sharedResourceTarget).build();

    // no preferredLinkage, shared flavor
    BuildTarget fooLibTarget =
        BuildTargetFactory.newInstance("//foo:lib#" + CxxDescriptionEnhancer.SHARED_FLAVOR);
    TargetNode<?, ?> fooLibNode =
        AppleLibraryBuilder.createBuilder(fooLibTarget)
            .setDeps(ImmutableSortedSet.of(sharedResourceTarget))
            .build();

    // shared preferredLinkage, no flavor
    BuildTarget foo2LibTarget = BuildTargetFactory.newInstance("//foo2:lib");
    TargetNode<?, ?> foo2LibNode =
        AppleLibraryBuilder.createBuilder(foo2LibTarget)
            .setDeps(ImmutableSortedSet.of(sharedResourceTarget))
            .setPreferredLinkage(NativeLinkable.Linkage.SHARED)
            .build();

    BuildTarget fooFrameworkTarget = BuildTargetFactory.newInstance("//foo:framework#default");
    TargetNode<?, ?> fooFrameworkNode =
        AppleBundleBuilder.createBuilder(fooFrameworkTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setBinary(fooLibTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    // shared preferredLinkage overriden by static flavor should still propagate dependencies.
    BuildTarget staticResourceTarget = BuildTargetFactory.newInstance("//static:resource");
    TargetNode<?, ?> staticResourceNode =
        AppleResourceBuilder.createBuilder(staticResourceTarget).build();

    BuildTarget bazLibTarget =
        BuildTargetFactory.newInstance("//baz:lib#" + CxxDescriptionEnhancer.STATIC_FLAVOR);
    TargetNode<?, ?> bazLibNode =
        AppleLibraryBuilder.createBuilder(bazLibTarget)
            .setDeps(ImmutableSortedSet.of(staticResourceTarget))
            .setPreferredLinkage(NativeLinkable.Linkage.SHARED)
            .build();

    BuildTarget barBinaryTarget = BuildTargetFactory.newInstance("//bar:binary");
    TargetNode<?, ?> barBinaryNode =
        AppleBinaryBuilder.createBuilder(barBinaryTarget)
            .setDeps(ImmutableSortedSet.of(fooLibTarget, foo2LibTarget, bazLibTarget))
            .build();

    BuildTarget barAppTarget = BuildTargetFactory.newInstance("//bar:app");
    TargetNode<?, ?> barAppNode =
        AppleBundleBuilder.createBuilder(barAppTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setBinary(barBinaryTarget)
            .setDeps(ImmutableSortedSet.of(fooFrameworkTarget))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    ImmutableSet<TargetNode<?, ?>> targetNodes =
        ImmutableSet.<TargetNode<?, ?>>builder()
            .add(
                sharedResourceNode,
                staticResourceNode,
                fooLibNode,
                foo2LibNode,
                bazLibNode,
                fooFrameworkNode,
                barBinaryNode,
                barAppNode)
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(targetNodes);
    Optional<AppleDependenciesCache> cache =
        useCache ? Optional.of(new AppleDependenciesCache(targetGraph)) : Optional.empty();

    for (int i = 0; i < (useCache ? 2 : 1); i++) {
      Iterable<TargetNode<?, ?>> rules =
          AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
              targetGraph,
              cache,
              AppleBuildRules.RecursiveDependenciesMode.COPYING,
              fooFrameworkNode,
              IS_APPLE_BUNDLE_RESOURCE_NODE,
              Optional.empty());

      assertEquals(ImmutableSortedSet.of(sharedResourceNode), ImmutableSortedSet.copyOf(rules));

      rules =
          AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
              targetGraph,
              cache,
              AppleBuildRules.RecursiveDependenciesMode.COPYING,
              barAppNode,
              IS_APPLE_BUNDLE_RESOURCE_NODE,
              Optional.empty());

      assertEquals(ImmutableSortedSet.of(staticResourceNode), ImmutableSortedSet.copyOf(rules));
    }
  }

  @Test
  public void linkingStopsAtGenruleDep() {
    // Pass a random static lib in a genrule and make sure a framework
    // depending on the genrule doesn't link against or copy in the static lib.
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo:lib");
    TargetNode<?, ?> fooLibNode = AppleLibraryBuilder.createBuilder(fooLibTarget).build();

    BuildTarget fooGenruleTarget = BuildTargetFactory.newInstance("//foo:genrule");
    TargetNode<?, ?> fooGenruleNode =
        GenruleBuilder.newGenruleBuilder(fooGenruleTarget)
            .setOut("foo")
            .setCmd("echo hi > $OUT")
            .setSrcs(ImmutableList.of(DefaultBuildTargetSourcePath.of(fooLibTarget)))
            .build();

    BuildTarget barLibTarget =
        BuildTargetFactory.newInstance("//bar:lib#" + CxxDescriptionEnhancer.SHARED_FLAVOR);
    TargetNode<?, ?> barLibNode =
        AppleLibraryBuilder.createBuilder(barLibTarget)
            .setDeps(ImmutableSortedSet.of(fooGenruleTarget))
            .build();
    BuildTarget barFrameworkTarget = BuildTargetFactory.newInstance("//bar:framework");
    TargetNode<?, ?> barFrameworkNode =
        AppleBundleBuilder.createBuilder(barFrameworkTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setBinary(barLibTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    ImmutableSet<TargetNode<?, ?>> targetNodes =
        ImmutableSet.<TargetNode<?, ?>>builder()
            .add(fooLibNode, fooGenruleNode, barLibNode, barFrameworkNode)
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(targetNodes);
    Optional<AppleDependenciesCache> cache =
        useCache ? Optional.of(new AppleDependenciesCache(targetGraph)) : Optional.empty();

    for (int i = 0; i < (useCache ? 2 : 1); i++) {
      Iterable<TargetNode<?, ?>> rules =
          AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
              targetGraph,
              cache,
              AppleBuildRules.RecursiveDependenciesMode.LINKING,
              barFrameworkNode,
              Optional.empty());

      assertEquals(ImmutableSortedSet.of(fooGenruleNode), ImmutableSortedSet.copyOf(rules));
    }
  }

  @Test
  public void copyingStopsAtGenruleDep() {
    // Pass a random static lib in a genrule and make sure a framework
    // depending on the genrule doesn't link against or copy in the static lib.
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo:lib");
    TargetNode<?, ?> fooLibNode = AppleLibraryBuilder.createBuilder(fooLibTarget).build();

    BuildTarget fooGenruleTarget = BuildTargetFactory.newInstance("//foo:genrule");
    TargetNode<?, ?> fooGenruleNode =
        GenruleBuilder.newGenruleBuilder(fooGenruleTarget)
            .setOut("foo")
            .setCmd("echo hi > $OUT")
            .setSrcs(ImmutableList.of(DefaultBuildTargetSourcePath.of(fooLibTarget)))
            .build();

    BuildTarget barLibTarget =
        BuildTargetFactory.newInstance("//bar:lib#" + CxxDescriptionEnhancer.SHARED_FLAVOR);
    TargetNode<?, ?> barLibNode =
        AppleLibraryBuilder.createBuilder(barLibTarget)
            .setDeps(ImmutableSortedSet.of(fooGenruleTarget))
            .build();
    BuildTarget barFrameworkTarget = BuildTargetFactory.newInstance("//bar:framework");
    TargetNode<?, ?> barFrameworkNode =
        AppleBundleBuilder.createBuilder(barFrameworkTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setBinary(barLibTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    ImmutableSet<TargetNode<?, ?>> targetNodes =
        ImmutableSet.<TargetNode<?, ?>>builder()
            .add(fooLibNode, fooGenruleNode, barLibNode, barFrameworkNode)
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(targetNodes);
    Optional<AppleDependenciesCache> cache =
        useCache ? Optional.of(new AppleDependenciesCache(targetGraph)) : Optional.empty();

    for (int i = 0; i < (useCache ? 2 : 1); i++) {
      Iterable<TargetNode<?, ?>> rules =
          AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
              targetGraph,
              cache,
              AppleBuildRules.RecursiveDependenciesMode.COPYING,
              barFrameworkNode,
              Optional.empty());

      assertEquals(ImmutableSortedSet.of(fooGenruleNode), ImmutableSortedSet.copyOf(rules));
    }
  }

  @Test
  public void buildingStopsAtGenruleDepButNotAtBundleDep() {
    // Pass a random static lib in a genrule and make sure a framework
    // depending on the genrule doesn't build the dependencies of that genrule.
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo:lib");
    TargetNode<?, ?> fooLibNode = AppleLibraryBuilder.createBuilder(fooLibTarget).build();

    BuildTarget fooGenruleTarget = BuildTargetFactory.newInstance("//foo:genrule");
    TargetNode<?, ?> fooGenruleNode =
        GenruleBuilder.newGenruleBuilder(fooGenruleTarget)
            .setOut("foo")
            .setCmd("echo hi > $OUT")
            .setSrcs(ImmutableList.of(DefaultBuildTargetSourcePath.of(fooLibTarget)))
            .build();

    BuildTarget barLibTarget =
        BuildTargetFactory.newInstance("//bar:lib#" + CxxDescriptionEnhancer.SHARED_FLAVOR);
    TargetNode<?, ?> barLibNode =
        AppleLibraryBuilder.createBuilder(barLibTarget)
            .setDeps(ImmutableSortedSet.of(fooGenruleTarget))
            .build();
    BuildTarget barFrameworkTarget = BuildTargetFactory.newInstance("//bar:framework");
    TargetNode<?, ?> barFrameworkNode =
        AppleBundleBuilder.createBuilder(barFrameworkTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setBinary(barLibTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    BuildTarget bazLibTarget =
        BuildTargetFactory.newInstance("//baz:lib#" + CxxDescriptionEnhancer.SHARED_FLAVOR);
    TargetNode<?, ?> bazLibNode =
        AppleLibraryBuilder.createBuilder(bazLibTarget)
            .setDeps(ImmutableSortedSet.of(barFrameworkTarget))
            .build();
    BuildTarget bazFrameworkTarget = BuildTargetFactory.newInstance("//baz:framework");
    TargetNode<?, ?> bazFrameworkNode =
        AppleBundleBuilder.createBuilder(bazFrameworkTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setBinary(bazLibTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
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

    TargetGraph targetGraph = TargetGraphFactory.newInstance(targetNodes);
    Optional<AppleDependenciesCache> cache =
        useCache ? Optional.of(new AppleDependenciesCache(targetGraph)) : Optional.empty();

    for (int i = 0; i < (useCache ? 2 : 1); i++) {
      Iterable<TargetNode<?, ?>> rules =
          AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
              targetGraph,
              cache,
              AppleBuildRules.RecursiveDependenciesMode.BUILDING,
              bazFrameworkNode,
              Optional.empty());

      assertEquals(
          ImmutableSortedSet.of(barFrameworkNode, fooGenruleNode),
          ImmutableSortedSet.copyOf(rules));
    }
  }

  @Test
  public void testDependenciesCache() {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//foo:lib");
    TargetNode<?, ?> libraryNode = AppleLibraryBuilder.createBuilder(libraryTarget).build();

    BuildTarget bundleTarget = BuildTargetFactory.newInstance("//foo:bundle");
    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.XCTEST))
            .setBinary(libraryTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    BuildTarget rootTarget = BuildTargetFactory.newInstance("//foo:root");
    TargetNode<?, ?> rootNode =
        AppleLibraryBuilder.createBuilder(rootTarget)
            .setDeps(ImmutableSortedSet.of(libraryTarget, bundleTarget))
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(ImmutableSet.of(libraryNode, bundleNode, rootNode));
    AppleDependenciesCache cache = new AppleDependenciesCache(targetGraph);

    ImmutableSortedSet<TargetNode<?, ?>> cachedDefaultDeps = cache.getDefaultDeps(rootNode);
    ImmutableSortedSet<TargetNode<?, ?>> cachedExportedDeps = cache.getExportedDeps(rootNode);

    assertEquals(cachedDefaultDeps, ImmutableSortedSet.of(bundleNode, libraryNode));
    assertEquals(cachedExportedDeps, ImmutableSortedSet.of());

    ImmutableSortedSet<TargetNode<?, ?>> defaultDeps = cache.getDefaultDeps(rootNode);
    ImmutableSortedSet<TargetNode<?, ?>> exportedDeps = cache.getExportedDeps(rootNode);

    assertSame(cachedDefaultDeps, defaultDeps);
    assertSame(cachedExportedDeps, exportedDeps);
  }
}
