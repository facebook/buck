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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.build.engine.RuleDepsCache;
import com.facebook.buck.core.build.engine.impl.DefaultRuleDepsCache;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.actiongraph.ActionGraph;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.cxx.CxxBinaryBuilder;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxTestBuilder;
import com.facebook.buck.cxx.CxxTestUtils;
import com.facebook.buck.cxx.SharedLibraryInterfacePlatforms;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkableDepType;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.features.filegroup.FilegroupBuilder;
import com.facebook.buck.features.lua.LuaBinaryBuilder;
import com.facebook.buck.features.lua.LuaLibraryBuilder;
import com.facebook.buck.features.lua.LuaTestUtils;
import com.facebook.buck.features.python.CxxPythonExtensionBuilder;
import com.facebook.buck.features.python.PythonBinaryBuilder;
import com.facebook.buck.features.python.PythonBuckConfig;
import com.facebook.buck.features.python.PythonLibraryBuilder;
import com.facebook.buck.features.python.PythonTestBuilder;
import com.facebook.buck.features.python.TestPythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonEnvironment;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonVersion;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.PrebuiltJarBuilder;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.keys.ContentAgnosticRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.shell.GenruleBuilder;
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
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class IncrementalActionGraphScenarioTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  private static final PythonPlatform PY2 = createPy2Platform(Optional.empty());
  private static final PythonPlatform PY3 = createPy3Platform(Optional.empty());

  private ProjectFilesystem filesystem;
  private BuckConfig buckConfig;
  private CxxBuckConfig cxxBuckConfig;
  private PythonBuckConfig pythonBuckConfig;
  private FlavorDomain<PythonPlatform> pythonPlatforms;
  private BuckEventBus eventBus;
  private RuleKeyFieldLoader fieldLoader;
  private ActionGraphCache cache;
  private ActionGraphProvider provider;

  @Before
  public void setUp() {
    filesystem = new FakeProjectFilesystem();
    buckConfig = FakeBuckConfig.builder().build();
    cxxBuckConfig = new CxxBuckConfig(buckConfig);
    pythonBuckConfig = new PythonBuckConfig(buckConfig);
    pythonPlatforms = FlavorDomain.of("Python Platform", PY2, PY3);
    eventBus = BuckEventBusForTests.newInstance();
    fieldLoader = new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create());
    cache = new ActionGraphCache(0);
    provider =
        new ActionGraphProviderBuilder()
            .withActionGraphCache(cache)
            .withRuleKeyConfiguration(TestRuleKeyConfigurationFactory.createWithSeed(0))
            .withEventBus(eventBus)
            .withIncrementalActionGraphMode(IncrementalActionGraphMode.ENABLED)
            .build();
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

    ActionGraphAndBuilder result =
        createActionGraph(binaryBuilder, libraryBuilder1, libraryBuilder2);
    queryTransitiveDeps(result);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);
    ActionGraphAndBuilder newResult =
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

    ActionGraphAndBuilder result =
        createActionGraph(binaryBuilder, libraryBuilder1, libraryBuilder2);
    // don't query for deps the first time, resulting in different caching behavior
    ActionGraphAndBuilder newResult =
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

    ActionGraphAndBuilder result =
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
    ActionGraphAndBuilder newResult =
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

    ActionGraphAndBuilder result =
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
    ActionGraphAndBuilder newResult =
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
    ActionGraphAndBuilder result = createActionGraph(versionedTargetGraph);
    queryTransitiveDeps(result);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);
    ActionGraphAndBuilder newResult = createActionGraph(versionedTargetGraph);
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

    ActionGraphAndBuilder result = createActionGraph(binaryBuilder, libraryBuilder);
    queryTransitiveDeps(result);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);
    ActionGraphAndBuilder newResult = createActionGraph(binaryBuilder, libraryBuilder);
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

    ActionGraphAndBuilder result = createActionGraph(binaryBuilder, sharedLibraryBuilder);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);

    // Make sure we actually created the shared library interface.
    BuildTarget sharedLibraryInterfaceTarget =
        CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
            sharedLibraryTarget, testPlatform.get().getFlavor(), Linker.LinkType.SHARED);
    assertTrue(
        "shared library interface didn't get created",
        result.getActionGraphBuilder().getRuleOptional(sharedLibraryInterfaceTarget).isPresent());

    ActionGraphAndBuilder newResult = createActionGraph(binaryBuilder, sharedLibraryBuilder);
    ImmutableMap<BuildRule, RuleKey> newRuleKeys = getRuleKeys(newResult);

    // Don't query transitive deps until after the second time, so we stress the cases where build
    // rules have to generate their deps (which they sometimes cache) using the new build rule
    // graphBuilder.
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

    ActionGraphAndBuilder result = createActionGraph(compilationDatabaseBuilder, libraryBuilder);

    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(binaryTarget)
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("bin.cpp"), ImmutableList.of())));

    ActionGraphAndBuilder newResult = createActionGraph(binaryBuilder, libraryBuilder);
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

    ActionGraphAndBuilder result = createActionGraph(versionedTargetGraph);

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

    ActionGraphAndBuilder newResult = createActionGraph(newVersionedTargetGraph);
    queryTransitiveDeps(newResult);

    assertBuildRulesSame(result, newResult, libraryTarget.getUnflavoredBuildTarget());
  }

  @Test
  public void testPythonBinaryAndLibraryLoadedFromCache() {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:python_library");
    PythonLibraryBuilder libraryBuilder =
        new PythonLibraryBuilder(libraryTarget, pythonPlatforms, CxxPlatformUtils.DEFAULT_PLATFORMS)
            .setSrcs(
                SourceSortedSet.ofUnnamedSources(
                    ImmutableSortedSet.of(FakeSourcePath.of("something.py"))));

    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:python_binary");
    PythonBinaryBuilder binaryBuilder =
        PythonBinaryBuilder.create(binaryTarget, pythonBuckConfig, pythonPlatforms)
            .setMainModule("main")
            .setPlatform(PY2.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(libraryTarget));

    ActionGraphAndBuilder result = createActionGraph(binaryBuilder, libraryBuilder);
    queryTransitiveDeps(result);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);
    ActionGraphAndBuilder newResult = createActionGraph(binaryBuilder, libraryBuilder);
    queryTransitiveDeps(newResult);
    ImmutableMap<BuildRule, RuleKey> newRuleKeys = getRuleKeys(newResult);

    assertBuildRulesSame(result, newResult);
    assertEquals(ruleKeys, newRuleKeys);
  }

  @Test
  public void testChangedPythonBinaryOnlyLibraryLoadedFromCache() {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:python_library");
    PythonLibraryBuilder libraryBuilder =
        new PythonLibraryBuilder(libraryTarget, pythonPlatforms, CxxPlatformUtils.DEFAULT_PLATFORMS)
            .setSrcs(
                SourceSortedSet.ofUnnamedSources(
                    ImmutableSortedSet.of(FakeSourcePath.of("something.py"))));

    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:python_binary");
    PythonBinaryBuilder binaryBuilder =
        PythonBinaryBuilder.create(binaryTarget, pythonBuckConfig, pythonPlatforms)
            .setMainModule("main")
            .setPlatform(PY2.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(libraryTarget));

    ActionGraphAndBuilder result = createActionGraph(binaryBuilder, libraryBuilder);
    queryTransitiveDeps(result);
    PythonBinaryBuilder newBinaryBuilder =
        PythonBinaryBuilder.create(binaryTarget, pythonBuckConfig, pythonPlatforms)
            .setMainModule("new")
            .setPlatform(PY2.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(libraryTarget));
    ActionGraphAndBuilder newResult = createActionGraph(newBinaryBuilder, libraryBuilder);
    queryTransitiveDeps(newResult);

    assertCommonBuildRulesNotSame(result, newResult, binaryTarget.getUnflavoredBuildTarget());
    assertBuildRulesSame(result, newResult, libraryTarget.getUnflavoredBuildTarget());
  }

  @Test
  public void testChangedPythonLibraryNotLoadedFromCache() {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:python_library");
    PythonLibraryBuilder libraryBuilder =
        new PythonLibraryBuilder(libraryTarget, pythonPlatforms, CxxPlatformUtils.DEFAULT_PLATFORMS)
            .setSrcs(
                SourceSortedSet.ofUnnamedSources(
                    ImmutableSortedSet.of(FakeSourcePath.of("something.py"))));

    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:python_binary");
    PythonBinaryBuilder binaryBuilder =
        PythonBinaryBuilder.create(binaryTarget, pythonBuckConfig, pythonPlatforms)
            .setMainModule("main")
            .setPlatform(PY2.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(libraryTarget));

    ActionGraphAndBuilder result = createActionGraph(binaryBuilder, libraryBuilder);
    queryTransitiveDeps(result);
    PythonLibraryBuilder newLibraryBuilder =
        new PythonLibraryBuilder(libraryTarget, pythonPlatforms, CxxPlatformUtils.DEFAULT_PLATFORMS)
            .setSrcs(
                SourceSortedSet.ofUnnamedSources(
                    ImmutableSortedSet.of(
                        FakeSourcePath.of("something.py"), FakeSourcePath.of("new.py"))));
    ActionGraphAndBuilder newResult = createActionGraph(binaryBuilder, newLibraryBuilder);
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

    ActionGraphAndBuilder result =
        createActionGraph(pythonBinaryBuilder, extensionBuilder, cxxLibraryBuilder);
    queryTransitiveDeps(result);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);
    ActionGraphAndBuilder newResult =
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

    ActionGraphAndBuilder result =
        createActionGraph(pythonBinaryBuilder, extensionBuilder, cxxLibraryBuilder);
    queryTransitiveDeps(result);

    PythonBinaryBuilder newPythonBinaryBuilder =
        PythonBinaryBuilder.create(pythonBinaryTarget, pythonBuckConfig, pythonPlatforms)
            .setMainModule("new")
            .setPlatform(PY2.getFlavor().toString())
            .setDeps(ImmutableSortedSet.of(extensionTarget));
    ActionGraphAndBuilder newResult =
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

    ActionGraphAndBuilder result =
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
    ActionGraphAndBuilder newResult =
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

    ActionGraphAndBuilder result = createActionGraph(binaryBuilder, genruleBuilder);
    queryTransitiveDeps(result);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);
    ActionGraphAndBuilder newResult = createActionGraph(binaryBuilder, genruleBuilder);
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

    ActionGraphAndBuilder result = createActionGraph(binaryBuilder, genruleBuilder);

    GenruleBuilder newGenruleBuilder =
        GenruleBuilder.newGenruleBuilder(genruleTarget).setCmd("cmd2").setOut("out");
    ActionGraphAndBuilder newResult = createActionGraph(binaryBuilder, newGenruleBuilder);
    queryTransitiveDeps(newResult);

    assertCommonBuildRulesNotSame(result, newResult, binaryTarget.getUnflavoredBuildTarget());
    assertCommonBuildRulesNotSame(result, newResult, genruleTarget.getUnflavoredBuildTarget());
  }

  @Test
  public void testFilegroupLoadedFromCache() {
    BuildTarget target = BuildTargetFactory.newInstance("//:group");
    FilegroupBuilder builder =
        FilegroupBuilder.createBuilder(target)
            .setSrcs(ImmutableSortedSet.of(FakeSourcePath.of("file.txt")));

    ActionGraphAndBuilder result = createActionGraph(builder);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);
    ActionGraphAndBuilder newResult = createActionGraph(builder);
    queryTransitiveDeps(newResult);
    ImmutableMap<BuildRule, RuleKey> newRuleKeys = getRuleKeys(newResult);

    assertBuildRulesSame(result, newResult);
    assertEquals(ruleKeys, newRuleKeys);
  }

  @Test
  public void testPrebuiltJarLoadedFromCache() {
    BuildTarget target = BuildTargetFactory.newInstance("//:jar");
    PrebuiltJarBuilder builder =
        PrebuiltJarBuilder.createBuilder(target).setBinaryJar(FakeSourcePath.of("app.jar"));

    ActionGraphAndBuilder result = createActionGraph(builder);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);
    ActionGraphAndBuilder newResult = createActionGraph(builder);
    queryTransitiveDeps(newResult);
    ImmutableMap<BuildRule, RuleKey> newRuleKeys = getRuleKeys(newResult);

    assertBuildRulesSame(result, newResult);
    assertEquals(ruleKeys, newRuleKeys);
  }

  @Test
  public void testLuaBinaryAndLibraryOnlyLibraryLoadedFromCache() {
    BuildTarget cxxLibraryTarget = BuildTargetFactory.newInstance("//:cxxlib");
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(cxxLibraryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("something.cpp"), ImmutableList.of())));

    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:lib");
    LuaLibraryBuilder libraryBuilder =
        new LuaLibraryBuilder(libraryTarget)
            .setSrcs(ImmutableSortedSet.of(FakeSourcePath.of("lib.lua")))
            .setDeps(ImmutableSortedSet.of(cxxLibraryTarget));

    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:bin");
    Tool override = new CommandTool.Builder().addArg("override").build();
    LuaBinaryBuilder binaryBuilder =
        new LuaBinaryBuilder(
                binaryTarget,
                LuaTestUtils.DEFAULT_PLATFORM
                    .withLua(new ConstantToolProvider(override))
                    .withExtension(".override"))
            .setMainModule("main")
            .setDeps(ImmutableSortedSet.of(libraryTarget));

    ActionGraphAndBuilder result =
        createActionGraph(binaryBuilder, libraryBuilder, cxxLibraryBuilder);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);

    ActionGraphAndBuilder newResult =
        createActionGraph(binaryBuilder, libraryBuilder, cxxLibraryBuilder);
    queryTransitiveDeps(newResult);
    ImmutableMap<BuildRule, RuleKey> newRuleKeys = getRuleKeys(newResult);

    assertBuildRulesSame(result, newResult, libraryTarget.getUnflavoredBuildTarget());
    assertBuildRulesSame(result, newResult, cxxLibraryTarget.getUnflavoredBuildTarget());
    assertCommonBuildRulesNotSame(result, newResult, binaryTarget.getUnflavoredBuildTarget());
    assertEquals(ruleKeys, newRuleKeys);
  }

  @Test
  public void testCxxTestWithMacroAndBinaryLoadedFromCache() {
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:bin");
    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(binaryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("bin.cpp"), ImmutableList.of())));

    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test");
    CxxTestBuilder testBuilder =
        new CxxTestBuilder(testTarget, cxxBuckConfig)
            .setDeps(ImmutableSortedSet.of(binaryTarget))
            .setEnv(
                ImmutableMap.of(
                    "TEST",
                    StringWithMacrosUtils.format("value %s", LocationMacro.of(binaryTarget))))
            .setArgs(
                ImmutableList.of(
                    StringWithMacrosUtils.format("value %s", LocationMacro.of(binaryTarget))))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("test.cpp"), ImmutableList.of())));

    ActionGraphAndBuilder result = createActionGraph(testBuilder, binaryBuilder);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);

    ActionGraphAndBuilder newResult = createActionGraph(testBuilder, binaryBuilder);
    queryTransitiveDeps(newResult);
    ImmutableMap<BuildRule, RuleKey> newRuleKeys = getRuleKeys(newResult);

    assertBuildRulesSame(result, newResult);
    assertEquals(ruleKeys, newRuleKeys);
  }

  @Test
  public void testCxxTestWithMacroAndBinaryLoadedFromCache_DelayRuleKeys() {
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:bin");
    CxxBinaryBuilder binaryBuilder =
        new CxxBinaryBuilder(binaryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("bin.cpp"), ImmutableList.of())));

    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test");
    CxxTestBuilder testBuilder =
        new CxxTestBuilder(testTarget, cxxBuckConfig)
            .setDeps(ImmutableSortedSet.of(binaryTarget))
            .setEnv(
                ImmutableMap.of(
                    "TEST",
                    StringWithMacrosUtils.format("value %s", LocationMacro.of(binaryTarget))))
            .setArgs(
                ImmutableList.of(
                    StringWithMacrosUtils.format("value %s", LocationMacro.of(binaryTarget))))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("test.cpp"), ImmutableList.of())));

    ActionGraphAndBuilder result = createActionGraph(testBuilder, binaryBuilder);

    // Calculating rulekeys immediately caches some things in the CxxTest. Make sure we compute them
    // for the first time only the second time around to check that we don't try to access an
    // invalidated BuildRuleResolver during the computation.
    ActionGraphAndBuilder newResult = createActionGraph(testBuilder, binaryBuilder);
    queryTransitiveDeps(newResult);
    getRuleKeys(newResult);

    assertBuildRulesSame(result, newResult);
  }

  @Test
  public void testPythonTestLoadedFromCache() {
    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:lib");
    PythonLibraryBuilder libraryBuilder =
        PythonLibraryBuilder.createBuilder(libraryTarget)
            .setSrcs(
                SourceSortedSet.ofUnnamedSources(
                    ImmutableSortedSet.of(FakeSourcePath.of("lib.py"))));

    BuildTarget testTarget = BuildTargetFactory.newInstance("//:test");
    PythonTestBuilder testBuilder =
        PythonTestBuilder.create(testTarget)
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .setSrcs(
                SourceSortedSet.ofUnnamedSources(
                    ImmutableSortedSet.of(FakeSourcePath.of("test.py"))));

    ActionGraphAndBuilder result = createActionGraph(testBuilder, libraryBuilder);
    ImmutableMap<BuildRule, RuleKey> ruleKeys = getRuleKeys(result);

    ActionGraphAndBuilder newResult = createActionGraph(testBuilder, libraryBuilder);
    queryTransitiveDeps(newResult);
    ImmutableMap<BuildRule, RuleKey> newRuleKeys = getRuleKeys(newResult);

    assertEquals(ruleKeys, newRuleKeys);
    assertBuildRulesSame(result, newResult);
  }

  @Test
  public void testChangeSkipActionGraphCacheValue() {
    BuildTarget target = BuildTargetFactory.newInstance("//:bin");
    CxxBinaryBuilder builder =
        new CxxBinaryBuilder(target)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary.cpp"), ImmutableList.of())));

    createActionGraph(TargetGraphFactory.newInstance(buildNodes(builder)));

    CxxBinaryBuilder builder2 =
        new CxxBinaryBuilder(target)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary2.cpp"), ImmutableList.of())));

    provider =
        new ActionGraphProviderBuilder()
            .withActionGraphCache(cache)
            .withRuleKeyConfiguration(TestRuleKeyConfigurationFactory.createWithSeed(0))
            .withEventBus(eventBus)
            .withSkipActionGraphCache()
            .build();

    createActionGraph(TargetGraphFactory.newInstance(buildNodes(builder2)));

    provider =
        new ActionGraphProviderBuilder()
            .withActionGraphCache(cache)
            .withRuleKeyConfiguration(TestRuleKeyConfigurationFactory.createWithSeed(0))
            .withEventBus(eventBus)
            .build();

    ActionGraphAndBuilder lastResult =
        createActionGraph(TargetGraphFactory.newInstance(buildNodes(builder)));
    assertFalse(Iterables.isEmpty(lastResult.getActionGraphBuilder().getBuildRules()));
  }

  @Test
  public void testBuildRuleResolverInActionGraphCacheNotInvalidated() {
    provider =
        new ActionGraphProviderBuilder()
            .withMaxEntries(2)
            .withRuleKeyConfiguration(TestRuleKeyConfigurationFactory.createWithSeed(0))
            .build();

    BuildTarget target = BuildTargetFactory.newInstance("//:bin");
    CxxBinaryBuilder builder =
        new CxxBinaryBuilder(target)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary.cpp"), ImmutableList.of())));

    createActionGraph(TargetGraphFactory.newInstance(buildNodes(builder)));

    CxxBinaryBuilder builder2 =
        new CxxBinaryBuilder(target)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary2.cpp"), ImmutableList.of())));
    createActionGraph(builder2);

    ActionGraphAndBuilder lastResult =
        createActionGraph(TargetGraphFactory.newInstance(buildNodes(builder)));
    assertFalse(Iterables.isEmpty(lastResult.getActionGraphBuilder().getBuildRules()));
  }

  @Test
  public void testBuildRuleResolverNotInActionGraphCacheInvalidated() {
    expectedException.expect(IllegalStateException.class);

    provider =
        new ActionGraphProviderBuilder()
            .withMaxEntries(2)
            .withRuleKeyConfiguration(TestRuleKeyConfigurationFactory.createWithSeed(0))
            .withIncrementalActionGraphMode(IncrementalActionGraphMode.ENABLED)
            .build();

    BuildTarget target = BuildTargetFactory.newInstance("//:bin");
    CxxBinaryBuilder builder =
        new CxxBinaryBuilder(target)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary.cpp"), ImmutableList.of())));

    ActionGraphAndBuilder originalResult = createActionGraph(builder);

    CxxBinaryBuilder builder2 =
        new CxxBinaryBuilder(target)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary2.cpp"), ImmutableList.of())));
    createActionGraph(builder2);

    CxxBinaryBuilder builder3 =
        new CxxBinaryBuilder(target)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary3.cpp"), ImmutableList.of())));
    createActionGraph(builder3);

    originalResult.getActionGraphBuilder().getBuildRules();
  }

  @Test
  public void testIncrementalityDisabledOnSkipActionGraphCache() {
    BuildTarget target = BuildTargetFactory.newInstance("//:bin");
    CxxBinaryBuilder builder =
        new CxxBinaryBuilder(target)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(FakeSourcePath.of("binary.cpp"), ImmutableList.of())));

    provider =
        new ActionGraphProviderBuilder()
            .withActionGraphCache(cache)
            .withRuleKeyConfiguration(TestRuleKeyConfigurationFactory.createWithSeed(0))
            .withEventBus(eventBus)
            .withSkipActionGraphCache()
            .build();

    ActionGraphAndBuilder firstResult =
        createActionGraph(TargetGraphFactory.newInstance(buildNodes(builder)));

    ActionGraphAndBuilder lastResult =
        createActionGraph(TargetGraphFactory.newInstance(buildNodes(builder)));

    assertCommonBuildRulesNotSame(firstResult, lastResult, target.getUnflavoredBuildTarget());
  }

  private void assertBuildRulesSame(
      ActionGraphAndBuilder expectedActionGraph, ActionGraphAndBuilder actualActionGraph) {
    assertBuildRulesSame(
        expectedActionGraph.getActionGraph().getNodes(),
        actualActionGraph.getActionGraph().getNodes());
  }

  private void assertBuildRulesSame(
      ActionGraphAndBuilder expectedActionGraph,
      ActionGraphAndBuilder actualActionGraph,
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
      ActionGraphAndBuilder lastActionGraph,
      ActionGraphAndBuilder newActionGraph,
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
      ActionGraphAndBuilder actionGraph, UnflavoredBuildTarget unflavoredTarget) {
    return RichStream.from(actionGraph.getActionGraph().getNodes())
        .filter(
            x ->
                x.getBuildTarget()
                    .getUnflavoredBuildTarget()
                    .getFullyQualifiedName()
                    .equals(unflavoredTarget.getFullyQualifiedName()))
        .collect(Collectors.toList());
  }

  private void queryTransitiveDeps(ActionGraphAndBuilder result) {
    Set<BuildRule> visited = new HashSet<>();
    RuleDepsCache depsCache = new DefaultRuleDepsCache(result.getActionGraphBuilder());
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

  private ImmutableMap<BuildRule, RuleKey> getRuleKeys(ActionGraphAndBuilder result) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(result.getActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ContentAgnosticRuleKeyFactory factory =
        new ContentAgnosticRuleKeyFactory(fieldLoader, pathResolver, ruleFinder, Optional.empty());

    ImmutableMap.Builder<BuildRule, RuleKey> builder = ImmutableMap.builder();
    for (BuildRule rule : result.getActionGraph().getNodes()) {
      builder.put(rule, factory.build(rule));
    }

    return builder.build();
  }

  private ActionGraphAndBuilder createActionGraph(TargetGraph targetGraph) {
    ActionGraphAndBuilder result = provider.getActionGraph(targetGraph);
    // Grab a copy of the data since we invalidate the collections in previous BuildRuleResolvers.
    return ActionGraphAndBuilder.of(
        new ActionGraph(
            RichStream.from(result.getActionGraph().getNodes()).collect(Collectors.toList())),
        result.getActionGraphBuilder());
  }

  private ActionGraphAndBuilder createActionGraph(TargetNode<?>... nodes) {
    // Use newInstanceExact for cases where we don't want unflavored versions of nodes to get added
    // implicitly.
    return createActionGraph(TargetGraphFactory.newInstanceExact(nodes));
  }

  private ActionGraphAndBuilder createActionGraph(AbstractNodeBuilder<?, ?, ?, ?>... builders) {
    ActionGraphAndBuilder result = createActionGraph(buildNodes(builders));

    // Grab a copy of the data since we invalidate the collections in previous BuildRuleResolvers.
    return ActionGraphAndBuilder.of(
        new ActionGraph(
            RichStream.from(result.getActionGraph().getNodes()).collect(Collectors.toList())),
        result.getActionGraphBuilder());
  }

  private TargetNode<?>[] buildNodes(AbstractNodeBuilder<?, ?, ?, ?>... builders) {
    return RichStream.from(builders)
        .map(builder -> builder.build(filesystem))
        .toArray(TargetNode<?>[]::new);
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
