/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.model.actiongraph.computation;

import static junit.framework.TestCase.assertSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.config.ActionGraphParallelizationMode;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.config.IncrementalActionGraphMode;
import com.facebook.buck.core.build.engine.RuleDepsCache;
import com.facebook.buck.core.build.engine.impl.DefaultRuleDepsCache;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.actiongraph.ActionGraph;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndResolver;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.CxxBinaryBuilder;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxTestUtils;
import com.facebook.buck.cxx.SharedLibraryInterfacePlatforms;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.features.python.CxxPythonExtensionBuilder;
import com.facebook.buck.features.python.PythonBinaryBuilder;
import com.facebook.buck.features.python.PythonBuckConfig;
import com.facebook.buck.features.python.PythonLibraryBuilder;
import com.facebook.buck.features.python.TestPythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonEnvironment;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonVersion;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.keys.ContentAgnosticRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionUniverse;
import com.facebook.buck.versions.VersionUniverseVersionSelector;
import com.facebook.buck.versions.VersionedAliasBuilder;
import com.facebook.buck.versions.VersionedTargetGraphBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

public class IncrementalActionGraphScenarioTest {
  private static final PythonPlatform PY2 = createPy2Platform(Optional.empty());
  private static final PythonPlatform PY3 = createPy3Platform(Optional.empty());

  private ProjectFilesystem filesystem;
  private BuckConfig buckConfig;
  private CxxBuckConfig cxxBuckConfig;
  private PythonBuckConfig pythonBuckConfig;
  private FlavorDomain<PythonPlatform> pythonPlatforms;
  private BuckEventBus eventBus;
  private CloseableMemoizedSupplier<ForkJoinPool> fakePoolSupplier;
  private RuleKeyFieldLoader fieldLoader;
  private ActionGraphCache cache;

  @Before
  public void setUp() {
    filesystem = new FakeProjectFilesystem();
    buckConfig = FakeBuckConfig.builder().build();
    cxxBuckConfig = new CxxBuckConfig(buckConfig);
    pythonBuckConfig = new PythonBuckConfig(buckConfig);
    pythonPlatforms = FlavorDomain.of("Python Platform", PY2, PY3);
    eventBus = BuckEventBusForTests.newInstance();
    fakePoolSupplier =
        CloseableMemoizedSupplier.of(
            () -> {
              throw new IllegalStateException(
                  "should not use parallel executor for single threaded action graph construction in test");
            },
            ignored -> {});
    fieldLoader = new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create());
    cache = new ActionGraphCache(1);
  }

  @Test
  public void testCxxBinaryAndLibrariesWithSourcesLoadedFromCache() {
    BuildTarget libraryTarget2 = BuildTargetFactory.newInstance("//:cxx_library2");
    CxxLibraryBuilder libraryBuilder2 =
        new CxxLibraryBuilder(libraryTarget2)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private2.h")))
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("public2.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("library2.cpp"), ImmutableList.of())));
    BuildTarget libraryTarget1 = BuildTargetFactory.newInstance("//:cxx_library1");
    CxxLibraryBuilder libraryBuilder1 =
        new CxxLibraryBuilder(libraryTarget1)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private1.h")))
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("public1.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("library1.cpp"), ImmutableList.of())))
            .setDeps(ImmutableSortedSet.of(libraryTarget2));
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:cxx_binary");
    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(binaryTarget)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("binary.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary.cpp"), ImmutableList.of())))
            .setDeps(ImmutableSortedSet.of(libraryTarget1));

    ActionGraphAndResolver result =
        createActionGraph(binaryBuilder, libraryBuilder1, libraryBuilder2);
    queryTransitiveDeps(result);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);
    ActionGraphAndResolver newResult =
        createActionGraph(binaryBuilder, libraryBuilder1, libraryBuilder2);
    queryTransitiveDeps(newResult);
    ImmutableMap<BuildRule, RuleKey> newRuleKeys = getRuleKeys(newResult);

    assertBuildRulesSame(result, newResult);
    assertEquals(ruleKeys, newRuleKeys);
  }

  @Test
  public void testCxxBinaryAndLibrariesWithSourcesLoadedFromCache_OnlyQueryDepsSecondTime() {
    BuildTarget libraryTarget2 = BuildTargetFactory.newInstance("//:cxx_library2");
    CxxLibraryBuilder libraryBuilder2 =
        new CxxLibraryBuilder(libraryTarget2)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private2.h")))
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("public2.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("library2.cpp"), ImmutableList.of())));
    BuildTarget libraryTarget1 = BuildTargetFactory.newInstance("//:cxx_library1");
    CxxLibraryBuilder libraryBuilder1 =
        new CxxLibraryBuilder(libraryTarget1)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private1.h")))
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("public1.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("library1.cpp"), ImmutableList.of())))
            .setDeps(ImmutableSortedSet.of(libraryTarget2));
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:cxx_binary");
    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(binaryTarget)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("binary.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary.cpp"), ImmutableList.of())))
            .setDeps(ImmutableSortedSet.of(libraryTarget1));

    ActionGraphAndResolver result =
        createActionGraph(binaryBuilder, libraryBuilder1, libraryBuilder2);
    // don't query for deps the first time, resulting in different caching behavior
    ActionGraphAndResolver newResult =
        createActionGraph(binaryBuilder, libraryBuilder1, libraryBuilder2);
    queryTransitiveDeps(newResult);

    assertBuildRulesSame(result, newResult);
  }

  @Test
  public void testCxxBinaryAndLibrariesWithChangedBinaryOnlyLibrariesLoadedFromCache() {
    BuildTarget libraryTarget2 = BuildTargetFactory.newInstance("//:cxx_library2");
    CxxLibraryBuilder libraryBuilder2 =
        new CxxLibraryBuilder(libraryTarget2)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private2.h")))
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("public2.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("library2.cpp"), ImmutableList.of())));
    BuildTarget libraryTarget1 = BuildTargetFactory.newInstance("//:cxx_library1");
    CxxLibraryBuilder libraryBuilder1 =
        new CxxLibraryBuilder(libraryTarget1)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private1.h")))
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("public1.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("library1.cpp"), ImmutableList.of())))
            .setDeps(ImmutableSortedSet.of(libraryTarget2));
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:cxx_binary");
    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(binaryTarget)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("binary.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary.cpp"), ImmutableList.of())))
            .setDeps(ImmutableSortedSet.of(libraryTarget1));

    ActionGraphAndResolver result =
        createActionGraph(binaryBuilder, libraryBuilder1, libraryBuilder2);
    queryTransitiveDeps(result);

    CxxBinaryBuilder newBinaryBuilder =
        new CxxBinaryBuilder(binaryTarget)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("binary.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary.cpp"), ImmutableList.of()),
                    SourceWithFlags.of(FakeSourcePath.of("new.cpp"), ImmutableList.of())))
            .setDeps(ImmutableSortedSet.of(libraryTarget1));
    ActionGraphAndResolver newResult =
        createActionGraph(newBinaryBuilder, libraryBuilder1, libraryBuilder2);
    queryTransitiveDeps(newResult);

    assertCommonBuildRulesNotSame(result, newResult, binaryTarget.getUnflavoredBuildTarget());
    assertBuildRulesSame(result, newResult, libraryTarget1.getUnflavoredBuildTarget());
    assertBuildRulesSame(result, newResult, libraryTarget2.getUnflavoredBuildTarget());
  }

  @Test
  public void testCxxBinaryAndLibrariesWithChangedLibraryNotLoadedFromCache() {
    BuildTarget libraryTarget2 = BuildTargetFactory.newInstance("//:cxx_library2");
    CxxLibraryBuilder libraryBuilder2 =
        new CxxLibraryBuilder(libraryTarget2)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private2.h")))
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("public2.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("library2.cpp"), ImmutableList.of())));
    BuildTarget libraryTarget1 = BuildTargetFactory.newInstance("//:cxx_library1");
    CxxLibraryBuilder libraryBuilder1 =
        new CxxLibraryBuilder(libraryTarget1)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private1.h")))
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("public1.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("library1.cpp"), ImmutableList.of())))
            .setDeps(ImmutableSortedSet.of(libraryTarget2));
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:cxx_binary");
    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(binaryTarget)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("binary.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary.cpp"), ImmutableList.of())))
            .setDeps(ImmutableSortedSet.of(libraryTarget1));

    ActionGraphAndResolver result =
        createActionGraph(binaryBuilder, libraryBuilder1, libraryBuilder2);
    queryTransitiveDeps(result);

    CxxLibraryBuilder newLibraryBuilder2 =
        new CxxLibraryBuilder(libraryTarget2)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private2.h")))
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("public2.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("library2.cpp"), ImmutableList.of()),
                    SourceWithFlags.of(FakeSourcePath.of("new.cpp"), ImmutableList.of())));
    ActionGraphAndResolver newResult =
        createActionGraph(binaryBuilder, libraryBuilder1, newLibraryBuilder2);
    queryTransitiveDeps(newResult);

    assertCommonBuildRulesNotSame(result, newResult, binaryTarget.getUnflavoredBuildTarget());
    assertCommonBuildRulesNotSame(result, newResult, libraryTarget1.getUnflavoredBuildTarget());
    assertCommonBuildRulesNotSame(result, newResult, libraryTarget2.getUnflavoredBuildTarget());
  }

  @Test
  public void testVersionedCxxBinaryAndLibrariesWithHeaderMapLoadedFromCache() throws Exception {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:cxx_library");
    CxxLibraryBuilder libraryBuilder =
        new CxxLibraryBuilder(libraryTarget)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private.h")))
            .setHeaderNamespace("myspace")
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("public.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("library.cpp"), ImmutableList.of())));
    BuildTarget versionedAliasTarget = BuildTargetFactory.newInstance("//:dep");
    VersionedAliasBuilder versionedAliasBuilder =
        new VersionedAliasBuilder(versionedAliasTarget)
            .setVersions(
                ImmutableMap.of(
                    Version.of("1.0"), libraryTarget,
                    Version.of("2.0"), libraryTarget));
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:cxx_binary");
    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(binaryTarget)
            .setVersionUniverse("1")
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary.cpp"), ImmutableList.of())))
            .setDeps(ImmutableSortedSet.of(versionedAliasTarget));
    BuildTarget binaryTarget2 = BuildTargetFactory.newInstance("//:cxx_binary2");
    CxxBinaryBuilder binaryBuilder2 =
        new CxxBinaryBuilder(binaryTarget2)
            .setVersionUniverse("2")
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary.cpp"), ImmutableList.of())))
            .setDeps(ImmutableSortedSet.of(versionedAliasTarget));

    VersionUniverse universe1 =
        VersionUniverse.of(ImmutableMap.of(versionedAliasTarget, Version.of("1.0")));
    VersionUniverse universe2 =
        VersionUniverse.of(ImmutableMap.of(versionedAliasTarget, Version.of("2.0")));

    TargetGraph unversionedTargetGraph =
        TargetGraphFactory.newInstance(
            binaryBuilder.build(filesystem),
            binaryBuilder2.build(filesystem),
            versionedAliasBuilder.build(filesystem),
            libraryBuilder.build(filesystem));
    TargetGraph versionedTargetGraph =
        VersionedTargetGraphBuilder.transform(
                new VersionUniverseVersionSelector(
                    unversionedTargetGraph, ImmutableMap.of("1", universe1, "2", universe2)),
                TargetGraphAndBuildTargets.of(
                    unversionedTargetGraph, ImmutableSet.of(binaryTarget, binaryTarget2)),
                new ForkJoinPool(),
                new DefaultTypeCoercerFactory())
            .getTargetGraph();
    ActionGraphAndResolver result = createActionGraph(versionedTargetGraph);
    queryTransitiveDeps(result);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);
    ActionGraphAndResolver newResult = createActionGraph(versionedTargetGraph);
    queryTransitiveDeps(newResult);
    ImmutableMap<BuildRule, RuleKey> newRuleKeys = getRuleKeys(newResult);

    assertBuildRulesSame(result, newResult);
    assertEquals(ruleKeys, newRuleKeys);
  }

  @Test
  public void testCxxBinaryWithoutSourcesAndLibraryLoadedFromCache() {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:cxx_library1");
    CxxLibraryBuilder libraryBuilder =
        new CxxLibraryBuilder(libraryTarget)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private.h")))
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("public.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("something.cpp"), ImmutableList.of())));
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:cxx_binary");
    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(binaryTarget).setDeps(ImmutableSortedSet.of(libraryTarget));

    ActionGraphAndResolver result = createActionGraph(binaryBuilder, libraryBuilder);
    queryTransitiveDeps(result);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);
    ActionGraphAndResolver newResult = createActionGraph(binaryBuilder, libraryBuilder);
    queryTransitiveDeps(newResult);
    ImmutableMap<BuildRule, RuleKey> newRuleKeys = getRuleKeys(newResult);

    assertBuildRulesSame(result, newResult);
    assertEquals(ruleKeys, newRuleKeys);
  }

  @Test
  public void testElfSharedLibraryLoadedFromCache() {
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                "[cxx]",
                "shlib_interfaces=enabled",
                "independent_shlib_interfaces=true",
                "objcopy=/usr/bin/objcopy")
            .build();
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);

    Optional<CxxPlatform> testPlatform =
        SharedLibraryInterfacePlatforms.getTestPlatform(filesystem, buckConfig, cxxBuckConfig);
    assumeTrue(testPlatform.isPresent());
    FlavorDomain cxxFlavorDomain = FlavorDomain.of("C/C++ Platform", testPlatform.get());

    BuildTarget sharedLibraryTarget = BuildTargetFactory.newInstance("//:shared_lib");
    CxxLibraryBuilder sharedLibraryBuilder =
        new CxxLibraryBuilder(sharedLibraryTarget, cxxBuckConfig, cxxFlavorDomain)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("shared_lib.cpp"), ImmutableList.of())));

    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:bin");
    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(binaryTarget, testPlatform.get(), cxxFlavorDomain, cxxBuckConfig)
            .setLinkStyle(LinkableDepType.SHARED)
            .setDeps(ImmutableSortedSet.of(sharedLibraryTarget))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("bin.cpp"), ImmutableList.of())));

    ActionGraphAndResolver result = createActionGraph(binaryBuilder, sharedLibraryBuilder);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);

    // Make sure we actually created the shared library interface.
    BuildTarget sharedLibraryInterfaceTarget =
        CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
            sharedLibraryTarget, testPlatform.get().getFlavor(), Linker.LinkType.SHARED);
    assertTrue(
        "shared library interface didn't get created",
        result.getResolver().getRuleOptional(sharedLibraryInterfaceTarget).isPresent());

    ActionGraphAndResolver newResult = createActionGraph(binaryBuilder, sharedLibraryBuilder);
    ImmutableMap<BuildRule, RuleKey> newRuleKeys = getRuleKeys(newResult);

    // Don't query transitive deps until after the second time, so we stress the cases where build
    // rules have to generate their deps (which they sometimes cache) using the new build rule
    // resolver.
    queryTransitiveDeps(newResult);

    assertBuildRulesSame(result, newResult);
    assertEquals(ruleKeys, newRuleKeys);
  }

  @Test
  public void testCxxBinaryCompilationDatabaseFollowedByRegularBinaryDepLoadedFromCache() {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:lib");
    CxxLibraryBuilder libraryBuilder =
        new CxxLibraryBuilder(libraryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("lib.cpp"), ImmutableList.of())));

    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:bin");
    BuildTarget compilationDatabaseTarget =
        binaryTarget.withFlavors(CxxCompilationDatabase.COMPILATION_DATABASE);
    CxxBinaryBuilder compilationDatabaseBuilder =
        new CxxBinaryBuilder(compilationDatabaseTarget)
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("bin.cpp"), ImmutableList.of())));

    ActionGraphAndResolver result = createActionGraph(compilationDatabaseBuilder, libraryBuilder);

    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(binaryTarget)
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("bin.cpp"), ImmutableList.of())));

    ActionGraphAndResolver newResult = createActionGraph(binaryBuilder, libraryBuilder);
    queryTransitiveDeps(newResult);

    assertBuildRulesSame(result, newResult, libraryTarget.getUnflavoredBuildTarget());
  }

  @Test
  public void testVersionedCxxBinaryCompilationDatabaseFollowedByRegularBinaryDepLoadedFromCache()
      throws Exception {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:lib");
    CxxLibraryBuilder libraryBuilder =
        new CxxLibraryBuilder(libraryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("lib.cpp"), ImmutableList.of())));
    BuildTarget versionedAliasTarget = BuildTargetFactory.newInstance("//:dep");
    VersionedAliasBuilder versionedAliasBuilder =
        new VersionedAliasBuilder(versionedAliasTarget)
            .setVersions(
                ImmutableMap.of(
                    Version.of("1.0"), libraryTarget,
                    Version.of("2.0"), libraryTarget));
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:bin");
    BuildTarget compilationDatabaseTarget =
        binaryTarget.withFlavors(CxxCompilationDatabase.COMPILATION_DATABASE);
    CxxBinaryBuilder compilationDatabaseBuilder =
        new CxxBinaryBuilder(compilationDatabaseTarget)
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("bin.cpp"), ImmutableList.of())));

    VersionUniverse universe1 =
        VersionUniverse.of(ImmutableMap.of(versionedAliasTarget, Version.of("1.0")));
    VersionUniverse universe2 =
        VersionUniverse.of(ImmutableMap.of(versionedAliasTarget, Version.of("2.0")));

    TargetGraph unversionedTargetGraph =
        TargetGraphFactory.newInstance(
            compilationDatabaseBuilder.build(filesystem),
            versionedAliasBuilder.build(filesystem),
            libraryBuilder.build(filesystem));
    TargetGraph versionedTargetGraph =
        VersionedTargetGraphBuilder.transform(
                new VersionUniverseVersionSelector(
                    unversionedTargetGraph, ImmutableMap.of("1", universe1, "2", universe2)),
                TargetGraphAndBuildTargets.of(
                    unversionedTargetGraph, ImmutableSet.of(compilationDatabaseTarget)),
                new ForkJoinPool(),
                new DefaultTypeCoercerFactory())
            .getTargetGraph();

    ActionGraphAndResolver result = createActionGraph(versionedTargetGraph);

    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(binaryTarget)
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("bin.cpp"), ImmutableList.of())));

    TargetGraph newUnversionedTargetGraph =
        TargetGraphFactory.newInstance(
            binaryBuilder.build(filesystem),
            versionedAliasBuilder.build(filesystem),
            libraryBuilder.build(filesystem));
    TargetGraph newVersionedTargetGraph =
        VersionedTargetGraphBuilder.transform(
                new VersionUniverseVersionSelector(
                    newUnversionedTargetGraph, ImmutableMap.of("1", universe1, "2", universe2)),
                TargetGraphAndBuildTargets.of(
                    newUnversionedTargetGraph, ImmutableSet.of(binaryTarget)),
                new ForkJoinPool(),
                new DefaultTypeCoercerFactory())
            .getTargetGraph();

    ActionGraphAndResolver newResult = createActionGraph(newVersionedTargetGraph);
    queryTransitiveDeps(newResult);

    assertBuildRulesSame(result, newResult, libraryTarget.getUnflavoredBuildTarget());
  }

  @Test
  public void testPythonBinaryAndLibraryLoadedFromCache() {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:python_library");
    PythonLibraryBuilder libraryBuilder =
        new PythonLibraryBuilder(libraryTarget)
            .setSrcs(
                SourceList.ofUnnamedSources(
                    ImmutableSortedSet.of(FakeSourcePath.of("something.py"))));

    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:python_binary");
    PythonBinaryBuilder binaryBuilder =
        PythonBinaryBuilder.create(binaryTarget, pythonBuckConfig, pythonPlatforms)
            .setMainModule("main")
            .setPlatform(PY2.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(libraryTarget));

    ActionGraphAndResolver result = createActionGraph(binaryBuilder, libraryBuilder);
    queryTransitiveDeps(result);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);
    ActionGraphAndResolver newResult = createActionGraph(binaryBuilder, libraryBuilder);
    queryTransitiveDeps(newResult);
    ImmutableMap<BuildRule, RuleKey> newRuleKeys = getRuleKeys(newResult);

    assertBuildRulesSame(result, newResult);
    assertEquals(ruleKeys, newRuleKeys);
  }

  @Test
  public void testChangedPythonBinaryOnlyLibraryLoadedFromCache() {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:python_library");
    PythonLibraryBuilder libraryBuilder =
        new PythonLibraryBuilder(libraryTarget)
            .setSrcs(
                SourceList.ofUnnamedSources(
                    ImmutableSortedSet.of(FakeSourcePath.of("something.py"))));

    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:python_binary");
    PythonBinaryBuilder binaryBuilder =
        PythonBinaryBuilder.create(binaryTarget, pythonBuckConfig, pythonPlatforms)
            .setMainModule("main")
            .setPlatform(PY2.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(libraryTarget));

    ActionGraphAndResolver result = createActionGraph(binaryBuilder, libraryBuilder);
    queryTransitiveDeps(result);
    PythonBinaryBuilder newBinaryBuilder =
        PythonBinaryBuilder.create(binaryTarget, pythonBuckConfig, pythonPlatforms)
            .setMainModule("new")
            .setPlatform(PY2.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(libraryTarget));
    ActionGraphAndResolver newResult = createActionGraph(newBinaryBuilder, libraryBuilder);
    queryTransitiveDeps(newResult);

    assertCommonBuildRulesNotSame(result, newResult, binaryTarget.getUnflavoredBuildTarget());
    assertBuildRulesSame(result, newResult, libraryTarget.getUnflavoredBuildTarget());
  }

  @Test
  public void testChangedPythonLibraryNotLoadedFromCache() {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:python_library");
    PythonLibraryBuilder libraryBuilder =
        new PythonLibraryBuilder(libraryTarget)
            .setSrcs(
                SourceList.ofUnnamedSources(
                    ImmutableSortedSet.of(FakeSourcePath.of("something.py"))));

    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:python_binary");
    PythonBinaryBuilder binaryBuilder =
        PythonBinaryBuilder.create(binaryTarget, pythonBuckConfig, pythonPlatforms)
            .setMainModule("main")
            .setPlatform(PY2.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(libraryTarget));

    ActionGraphAndResolver result = createActionGraph(binaryBuilder, libraryBuilder);
    queryTransitiveDeps(result);
    PythonLibraryBuilder newLibraryBuilder =
        new PythonLibraryBuilder(libraryTarget)
            .setSrcs(
                SourceList.ofUnnamedSources(
                    ImmutableSortedSet.of(
                        FakeSourcePath.of("something.py"), FakeSourcePath.of("new.py"))));
    ActionGraphAndResolver newResult = createActionGraph(binaryBuilder, newLibraryBuilder);
    queryTransitiveDeps(newResult);

    assertCommonBuildRulesNotSame(result, newResult, binaryTarget.getUnflavoredBuildTarget());
    assertCommonBuildRulesNotSame(result, newResult, libraryTarget.getUnflavoredBuildTarget());
  }

  @Test
  public void testCxxPythonExtensionLoadedFromCache() {
    BuildTarget cxxLibraryTarget = BuildTargetFactory.newInstance("//:cxx_library");
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(cxxLibraryTarget)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private.h")))
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("public.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("something.cpp"), ImmutableList.of())));

    BuildTarget extensionTarget = BuildTargetFactory.newInstance("//:cxx_python_extension");
    CxxPythonExtensionBuilder extensionBuilder =
        new CxxPythonExtensionBuilder(
                extensionTarget,
                pythonPlatforms,
                cxxBuckConfig,
                CxxTestUtils.createDefaultPlatforms())
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private.h")))
            .setDeps(ImmutableSortedSet.of(cxxLibraryTarget));

    BuildTarget pythonBinaryTarget = BuildTargetFactory.newInstance("//:python_binary");
    PythonBinaryBuilder pythonBinaryBuilder =
        PythonBinaryBuilder.create(pythonBinaryTarget, pythonBuckConfig, pythonPlatforms)
            .setMainModule("main")
            .setPlatform(PY2.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(extensionTarget));

    ActionGraphAndResolver result =
        createActionGraph(pythonBinaryBuilder, extensionBuilder, cxxLibraryBuilder);
    queryTransitiveDeps(result);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);
    ActionGraphAndResolver newResult =
        createActionGraph(pythonBinaryBuilder, extensionBuilder, cxxLibraryBuilder);
    queryTransitiveDeps(newResult);
    ImmutableMap<BuildRule, RuleKey> newRuleKeys = getRuleKeys(newResult);

    assertBuildRulesSame(result, newResult);
    assertEquals(ruleKeys, newRuleKeys);
  }

  @Test
  public void
      testChangedPythonBinaryWithCxxPythonExtensionOnlyLibraryAndExtensionLoadedFromCache() {
    BuildTarget cxxLibraryTarget = BuildTargetFactory.newInstance("//:cxx_library");
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(cxxLibraryTarget)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private.h")))
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("public.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("something.cpp"), ImmutableList.of())));

    FlavorDomain<PythonPlatform> pythonPlatforms = FlavorDomain.of("Python Platform", PY2, PY3);
    BuildTarget extensionTarget = BuildTargetFactory.newInstance("//:cxx_python_extension");
    CxxPythonExtensionBuilder extensionBuilder =
        new CxxPythonExtensionBuilder(
                extensionTarget,
                pythonPlatforms,
                cxxBuckConfig,
                CxxTestUtils.createDefaultPlatforms())
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private.h")))
            .setDeps(ImmutableSortedSet.of(cxxLibraryTarget));

    BuildTarget pythonBinaryTarget = BuildTargetFactory.newInstance("//:python_binary");
    PythonBinaryBuilder pythonBinaryBuilder =
        PythonBinaryBuilder.create(pythonBinaryTarget, pythonBuckConfig, pythonPlatforms)
            .setMainModule("main")
            .setPlatform(PY2.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(extensionTarget));

    ActionGraphAndResolver result =
        createActionGraph(pythonBinaryBuilder, extensionBuilder, cxxLibraryBuilder);
    queryTransitiveDeps(result);

    PythonBinaryBuilder newPythonBinaryBuilder =
        PythonBinaryBuilder.create(pythonBinaryTarget, pythonBuckConfig, pythonPlatforms)
            .setMainModule("new")
            .setPlatform(PY2.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(extensionTarget));
    ActionGraphAndResolver newResult =
        createActionGraph(newPythonBinaryBuilder, extensionBuilder, cxxLibraryBuilder);
    queryTransitiveDeps(newResult);

    assertCommonBuildRulesNotSame(result, newResult, pythonBinaryTarget.getUnflavoredBuildTarget());
    assertBuildRulesSame(result, newResult, cxxLibraryTarget.getUnflavoredBuildTarget());
    assertBuildRulesSame(result, newResult, extensionTarget.getUnflavoredBuildTarget());
  }

  @Test
  public void testPythonBinaryWithCxxPythonExtensionChangedLibraryNotLoadedFromCache() {
    BuildTarget cxxLibraryTarget = BuildTargetFactory.newInstance("//:cxx_library");
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(cxxLibraryTarget)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private.h")))
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("public.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("something.cpp"), ImmutableList.of())));

    FlavorDomain<PythonPlatform> pythonPlatforms = FlavorDomain.of("Python Platform", PY2, PY3);
    BuildTarget extensionTarget = BuildTargetFactory.newInstance("//:cxx_python_extension");
    CxxPythonExtensionBuilder extensionBuilder =
        new CxxPythonExtensionBuilder(
                extensionTarget,
                pythonPlatforms,
                cxxBuckConfig,
                CxxTestUtils.createDefaultPlatforms())
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private.h")))
            .setDeps(ImmutableSortedSet.of(cxxLibraryTarget));

    BuildTarget pythonBinaryTarget = BuildTargetFactory.newInstance("//:python_binary");
    PythonBinaryBuilder pythonBinaryBuilder =
        PythonBinaryBuilder.create(pythonBinaryTarget, pythonBuckConfig, pythonPlatforms)
            .setMainModule("main")
            .setPlatform(PY2.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(extensionTarget));

    ActionGraphAndResolver result =
        createActionGraph(pythonBinaryBuilder, extensionBuilder, cxxLibraryBuilder);
    queryTransitiveDeps(result);

    CxxLibraryBuilder newCxxLibraryBuilder =
        new CxxLibraryBuilder(cxxLibraryTarget)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("private.h")))
            .setExportedHeaders(ImmutableSortedSet.of(FakeSourcePath.of("public.h")))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("something.cpp"), ImmutableList.of()),
                    SourceWithFlags.of(FakeSourcePath.of("new.cpp"), ImmutableList.of())));
    ActionGraphAndResolver newResult =
        createActionGraph(pythonBinaryBuilder, extensionBuilder, newCxxLibraryBuilder);
    queryTransitiveDeps(newResult);

    assertCommonBuildRulesNotSame(result, newResult, pythonBinaryTarget.getUnflavoredBuildTarget());
    assertCommonBuildRulesNotSame(result, newResult, extensionTarget.getUnflavoredBuildTarget());
    assertCommonBuildRulesNotSame(result, newResult, cxxLibraryTarget.getUnflavoredBuildTarget());
  }

  @Test
  public void testCxxBinaryAndGenruleLoadedFromCache() {
    BuildTarget genruleTarget = BuildTargetFactory.newInstance("//:gen");
    GenruleBuilder genruleBuilder =
        GenruleBuilder.newGenruleBuilder(genruleTarget).setCmd("cmd").setOut("out");

    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:bin");
    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(binaryTarget)
            .setHeaders(ImmutableSortedSet.of(DefaultBuildTargetSourcePath.of(genruleTarget)))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary.cpp"), ImmutableList.of())));

    ActionGraphAndResolver result = createActionGraph(binaryBuilder, genruleBuilder);
    queryTransitiveDeps(result);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);
    ActionGraphAndResolver newResult = createActionGraph(binaryBuilder, genruleBuilder);
    queryTransitiveDeps(newResult);
    ImmutableMap<BuildRule, RuleKey> newRuleKeys = getRuleKeys(newResult);

    assertBuildRulesSame(result, newResult);
    assertEquals(ruleKeys, newRuleKeys);
  }

  @Test
  public void testCxxBinaryAndChangedGenruleNotLoadedFromCache() {
    BuildTarget genruleTarget = BuildTargetFactory.newInstance("//:gen");
    GenruleBuilder genruleBuilder =
        GenruleBuilder.newGenruleBuilder(genruleTarget).setCmd("cmd").setOut("out");

    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:bin");
    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(binaryTarget)
            .setHeaders(ImmutableSortedSet.of(DefaultBuildTargetSourcePath.of(genruleTarget)))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary.cpp"), ImmutableList.of())));

    ActionGraphAndResolver result = createActionGraph(binaryBuilder, genruleBuilder);

    GenruleBuilder newGenruleBuilder =
        GenruleBuilder.newGenruleBuilder(genruleTarget).setCmd("cmd2").setOut("out");
    ActionGraphAndResolver newResult = createActionGraph(binaryBuilder, newGenruleBuilder);
    queryTransitiveDeps(newResult);

    assertCommonBuildRulesNotSame(result, newResult, binaryTarget.getUnflavoredBuildTarget());
    assertCommonBuildRulesNotSame(result, newResult, genruleTarget.getUnflavoredBuildTarget());
  }

  private void assertBuildRulesSame(
      ActionGraphAndResolver expectedActionGraph, ActionGraphAndResolver actualActionGraph) {
    assertBuildRulesSame(
        expectedActionGraph.getActionGraph().getNodes(),
        actualActionGraph.getActionGraph().getNodes());
  }

  private void assertBuildRulesSame(
      ActionGraphAndResolver expectedActionGraph,
      ActionGraphAndResolver actualActionGraph,
      UnflavoredBuildTarget unflavoredTarget) {
    assertBuildRulesSame(
        filterRules(expectedActionGraph, unflavoredTarget),
        filterRules(actualActionGraph, unflavoredTarget));
  }

  private void assertBuildRulesSame(
      Iterable<BuildRule> expectedBuildRules, Iterable<BuildRule> actualBuildRules) {
    // First check value equality in the aggregate, immediately showing in test output what keys are
    // missing, if any.
    Set<String> expectedBuildRuleNames =
        RichStream.from(expectedBuildRules)
            .map(x -> x.getFullyQualifiedName())
            .sorted()
            .collect(ImmutableSet.toImmutableSet());
    Set<String> actualBuildRuleNames =
        RichStream.from(actualBuildRules)
            .map(x -> x.getFullyQualifiedName())
            .sorted()
            .collect(ImmutableSet.toImmutableSet());
    assertEquals(expectedBuildRuleNames, actualBuildRuleNames);

    // Now check reference equality.
    Map<BuildRule, BuildRule> actualBuildRuleMap = buildBuildRuleMap(actualBuildRules);
    for (BuildRule expectedBuildRule : expectedBuildRules) {
      assertSame(
          "BuildRule references are different",
          expectedBuildRule,
          actualBuildRuleMap.get(expectedBuildRule));
    }
  }

  private void assertCommonBuildRulesNotSame(
      ActionGraphAndResolver lastActionGraph,
      ActionGraphAndResolver newActionGraph,
      UnflavoredBuildTarget unflavoredTarget) {
    ImmutableMap<BuildRule, BuildRule> lastBuildRuleMap =
        buildBuildRuleMap(filterRules(lastActionGraph, unflavoredTarget));
    ImmutableMap<BuildRule, BuildRule> newBuildRuleMap =
        buildBuildRuleMap(filterRules(newActionGraph, unflavoredTarget));
    for (BuildRule buildRule : lastBuildRuleMap.keySet()) {
      BuildRule newBuildRule = newBuildRuleMap.get(buildRule);
      assertNotSame(buildRule.getFullyQualifiedName(), buildRule, newBuildRule);
    }
  }

  private Iterable<BuildRule> filterRules(
      ActionGraphAndResolver actionGraph, UnflavoredBuildTarget unflavoredTarget) {
    return RichStream.from(actionGraph.getActionGraph().getNodes())
        .filter(
            x ->
                x.getBuildTarget()
                    .getUnflavoredBuildTarget()
                    .getFullyQualifiedName()
                    .equals(unflavoredTarget.getFullyQualifiedName()))
        .collect(Collectors.toList());
  }

  private void queryTransitiveDeps(ActionGraphAndResolver result) {
    Set<BuildRule> visited = new HashSet<>();
    RuleDepsCache depsCache = new DefaultRuleDepsCache(result.getResolver());
    for (BuildRule buildRule : result.getActionGraph().getNodes()) {
      queryTransitiveDeps(buildRule, depsCache, visited);
    }
  }

  private void queryTransitiveDeps(
      BuildRule buildRule, RuleDepsCache depsCache, Set<BuildRule> visited) {
    if (visited.contains(buildRule)) {
      return;
    }
    visited.add(buildRule);
    for (BuildRule dep : depsCache.get(buildRule)) {
      queryTransitiveDeps(dep, depsCache, visited);
    }
  }

  private ImmutableMap<BuildRule, BuildRule> buildBuildRuleMap(Iterable<BuildRule> buildRules) {
    ImmutableMap.Builder<BuildRule, BuildRule> builder = ImmutableMap.builder();
    for (BuildRule buildRule : buildRules) {
      builder.put(buildRule, buildRule);
    }
    return builder.build();
  }

  private ImmutableMap<BuildRule, RuleKey> getRuleKeys(ActionGraphAndResolver result) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(result.getResolver());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ContentAgnosticRuleKeyFactory factory =
        new ContentAgnosticRuleKeyFactory(fieldLoader, pathResolver, ruleFinder, Optional.empty());

    ImmutableMap.Builder<BuildRule, RuleKey> builder = ImmutableMap.builder();
    for (BuildRule rule : result.getActionGraph().getNodes()) {
      builder.put(rule, factory.build(rule));
    }

    return builder.build();
  }

  private ActionGraphAndResolver createActionGraph(TargetGraph targetGraph) {
    ActionGraphAndResolver result =
        cache.getActionGraph(
            eventBus,
            false, /* checkActionGraphs */
            true, /* skipActionGraphCache */
            targetGraph,
            new TestCellBuilder().build().getCellProvider(),
            TestRuleKeyConfigurationFactory.createWithSeed(0),
            ActionGraphParallelizationMode.DISABLED,
            false,
            IncrementalActionGraphMode.ENABLED,
            fakePoolSupplier);
    // Grab a copy of the data since we invalidate the collections in previous BuildRuleResolvers.
    return ActionGraphAndResolver.of(
        new ActionGraph(
            RichStream.from(result.getActionGraph().getNodes()).collect(Collectors.toList())),
        result.getResolver());
  }

  private ActionGraphAndResolver createActionGraph(TargetNode<?, ?>... nodes) {
    // Use newInstanceExact for cases where we don't want unflavored versions of nodes to get added
    // implicitly.
    return createActionGraph(TargetGraphFactory.newInstanceExact(nodes));
  }

  private ActionGraphAndResolver createActionGraph(AbstractNodeBuilder<?, ?, ?, ?>... builders) {
    ActionGraphAndResolver result =
        createActionGraph(
            RichStream.from(builders)
                .map(builder -> builder.build(filesystem))
                .toArray(TargetNode<?, ?>[]::new));

    // Grab a copy of the data since we invalidate the collections in previous BuildRuleResolvers.
    return ActionGraphAndResolver.of(
        new ActionGraph(
            RichStream.from(result.getActionGraph().getNodes()).collect(Collectors.toList())),
        result.getResolver());
  }

  private static PythonPlatform createPy2Platform(Optional<BuildTarget> cxxLibrary) {
    return new TestPythonPlatform(
        InternalFlavor.of("py2"),
        new PythonEnvironment(Paths.get("python2"), PythonVersion.of("CPython", "2.6")),
        cxxLibrary);
  }

  private static PythonPlatform createPy3Platform(Optional<BuildTarget> cxxLibrary) {
    return new TestPythonPlatform(
        InternalFlavor.of("py3"),
        new PythonEnvironment(Paths.get("python3"), PythonVersion.of("CPython", "3.5")),
        cxxLibrary);
  }
}
