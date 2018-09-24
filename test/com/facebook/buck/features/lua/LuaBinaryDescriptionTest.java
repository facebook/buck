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

package com.facebook.buck.features.lua;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxTestUtils;
import com.facebook.buck.cxx.PrebuiltCxxLibraryBuilder;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.facebook.buck.features.python.CxxPythonExtensionBuilder;
import com.facebook.buck.features.python.PythonBinaryDescription;
import com.facebook.buck.features.python.PythonLibraryBuilder;
import com.facebook.buck.features.python.TestPythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonEnvironment;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatformsProvider;
import com.facebook.buck.features.python.toolchain.PythonVersion;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.AllExistingProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class LuaBinaryDescriptionTest {

  private static final BuildTarget PYTHON2_DEP_TARGET =
      BuildTargetFactory.newInstance("//:python2_dep");
  private static final PythonPlatform PY2 =
      new TestPythonPlatform(
          InternalFlavor.of("py2"),
          new PythonEnvironment(Paths.get("python2"), PythonVersion.of("CPython", "2.6")),
          Optional.of(PYTHON2_DEP_TARGET));

  private static final BuildTarget PYTHON3_DEP_TARGET =
      BuildTargetFactory.newInstance("//:python3_dep");
  private static final PythonPlatform PY3 =
      new TestPythonPlatform(
          InternalFlavor.of("py3"),
          new PythonEnvironment(Paths.get("python3"), PythonVersion.of("CPython", "3.5")),
          Optional.of(PYTHON3_DEP_TARGET));

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void mainModule() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    LuaBinary binary =
        new LuaBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setMainModule("hello.world")
            .build(graphBuilder);
    assertThat(binary.getMainModule(), Matchers.equalTo("hello.world"));
  }

  @Test
  public void extensionOverride() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    LuaBinary binary =
        new LuaBinaryBuilder(
                BuildTargetFactory.newInstance("//:rule"),
                LuaTestUtils.DEFAULT_PLATFORM.withExtension(".override"))
            .setMainModule("main")
            .build(graphBuilder);
    assertThat(
        pathResolver.getRelativePath(binary.getSourcePathToOutput()).toString(),
        Matchers.endsWith(".override"));
  }

  @Test
  public void toolOverride() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Tool override = new CommandTool.Builder().addArg("override").build();
    LuaBinary binary =
        new LuaBinaryBuilder(
                BuildTargetFactory.newInstance("//:rule"),
                LuaTestUtils.DEFAULT_PLATFORM
                    .withLua(new ConstantToolProvider(override))
                    .withExtension(".override"))
            .setMainModule("main")
            .build(graphBuilder);
    assertThat(binary.getLua(), Matchers.is(override));
  }

  @Test
  public void versionLessNativeLibraryExtension() {
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setSoname("libfoo.so.1.0")
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("hello.c"))));
    LuaBinaryBuilder binaryBuilder =
        new LuaBinaryBuilder(
                BuildTargetFactory.newInstance("//:rule"),
                LuaTestUtils.DEFAULT_PLATFORM.withPackageStyle(LuaPlatform.PackageStyle.INPLACE))
            .setMainModule("main")
            .setDeps(ImmutableSortedSet.of(cxxLibraryBuilder.getTarget()));
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(cxxLibraryBuilder.build(), binaryBuilder.build()));
    cxxLibraryBuilder.build(graphBuilder);
    binaryBuilder.build(graphBuilder);
    SymlinkTree tree =
        graphBuilder.getRuleWithType(
            LuaBinaryDescription.getNativeLibsSymlinkTreeTarget(binaryBuilder.getTarget()),
            SymlinkTree.class);
    assertThat(
        tree.getLinks().keySet(),
        Matchers.hasItem(tree.getProjectFilesystem().getPath("libfoo.so")));
  }

  @Test
  public void duplicateIdenticalModules() {
    LuaLibraryBuilder libraryABuilder =
        new LuaLibraryBuilder(BuildTargetFactory.newInstance("//:a"))
            .setSrcs(ImmutableSortedMap.of("foo.lua", FakeSourcePath.of("test")));
    LuaLibraryBuilder libraryBBuilder =
        new LuaLibraryBuilder(BuildTargetFactory.newInstance("//:b"))
            .setSrcs(ImmutableSortedMap.of("foo.lua", FakeSourcePath.of("test")));
    LuaBinaryBuilder binaryBuilder =
        new LuaBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setMainModule("hello.world")
            .setDeps(
                ImmutableSortedSet.of(libraryABuilder.getTarget(), libraryBBuilder.getTarget()));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            libraryABuilder.build(), libraryBBuilder.build(), binaryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    libraryABuilder.build(graphBuilder, filesystem, targetGraph);
    libraryBBuilder.build(graphBuilder, filesystem, targetGraph);
    binaryBuilder.build(graphBuilder, filesystem, targetGraph);
  }

  @Test
  public void duplicateConflictingModules() {
    LuaLibraryBuilder libraryABuilder =
        new LuaLibraryBuilder(BuildTargetFactory.newInstance("//:a"))
            .setSrcs(ImmutableSortedMap.of("foo.lua", FakeSourcePath.of("foo")));
    LuaLibraryBuilder libraryBBuilder =
        new LuaLibraryBuilder(BuildTargetFactory.newInstance("//:b"))
            .setSrcs(ImmutableSortedMap.of("foo.lua", FakeSourcePath.of("bar")));
    LuaBinaryBuilder binaryBuilder =
        new LuaBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setMainModule("hello.world")
            .setDeps(
                ImmutableSortedSet.of(libraryABuilder.getTarget(), libraryBBuilder.getTarget()));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            libraryABuilder.build(), libraryBBuilder.build(), binaryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    libraryABuilder.build(graphBuilder, filesystem, targetGraph);
    libraryBBuilder.build(graphBuilder, filesystem, targetGraph);
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(Matchers.containsString("conflicting modules for foo.lua"));
    binaryBuilder.build(graphBuilder, filesystem, targetGraph);
  }

  @Test
  public void pythonDeps() {
    PythonLibraryBuilder pythonLibraryBuilder =
        new PythonLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(
                SourceSortedSet.ofUnnamedSources(
                    ImmutableSortedSet.of(FakeSourcePath.of("foo.py"))));
    LuaBinaryBuilder luaBinaryBuilder =
        new LuaBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setMainModule("hello.world")
            .setDeps(ImmutableSortedSet.of(pythonLibraryBuilder.getTarget()));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(pythonLibraryBuilder.build(), luaBinaryBuilder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    pythonLibraryBuilder.build(graphBuilder, filesystem, targetGraph);
    LuaBinary luaBinary = luaBinaryBuilder.build(graphBuilder, filesystem, targetGraph);
    assertThat(luaBinary.getComponents().getPythonModules().keySet(), Matchers.hasItem("foo.py"));
  }

  @Test
  public void cxxPythonExtensionPlatformDeps() {
    FlavorDomain<PythonPlatform> pythonPlatforms = FlavorDomain.of("Python Platform", PY2, PY3);
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(FakeBuckConfig.builder().build());

    CxxLibraryBuilder py2LibBuilder = new CxxLibraryBuilder(PYTHON2_DEP_TARGET);
    CxxLibraryBuilder py3LibBuilder = new CxxLibraryBuilder(PYTHON3_DEP_TARGET);
    CxxLibraryBuilder py2CxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:py2_library"))
            .setSoname("libpy2.so")
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("hello.c"))));
    CxxLibraryBuilder py3CxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:py3_library"))
            .setSoname("libpy3.so")
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("hello.c"))));
    CxxPythonExtensionBuilder cxxPythonExtensionBuilder =
        new CxxPythonExtensionBuilder(
                BuildTargetFactory.newInstance("//:extension"),
                pythonPlatforms,
                cxxBuckConfig,
                CxxTestUtils.createDefaultPlatforms())
            .setPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(PY2.getFlavor().toString()),
                        ImmutableSortedSet.of(py2CxxLibraryBuilder.getTarget()))
                    .add(
                        Pattern.compile(PY3.getFlavor().toString()),
                        ImmutableSortedSet.of(py3CxxLibraryBuilder.getTarget()))
                    .build());
    LuaBinaryBuilder luaBinaryBuilder =
        new LuaBinaryBuilder(
                new LuaBinaryDescription(
                    new ToolchainProviderBuilder()
                        .withToolchain(
                            LuaPlatformsProvider.DEFAULT_NAME,
                            LuaPlatformsProvider.of(
                                LuaTestUtils.DEFAULT_PLATFORM, LuaTestUtils.DEFAULT_PLATFORMS))
                        .withToolchain(
                            PythonPlatformsProvider.DEFAULT_NAME,
                            PythonPlatformsProvider.of(pythonPlatforms))
                        .build(),
                    cxxBuckConfig),
                BuildTargetFactory.newInstance("//:binary"))
            .setMainModule("main")
            .setDeps(ImmutableSortedSet.of(cxxPythonExtensionBuilder.getTarget()));

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(
                py2LibBuilder.build(),
                py3LibBuilder.build(),
                py2CxxLibraryBuilder.build(),
                py3CxxLibraryBuilder.build(),
                cxxPythonExtensionBuilder.build(),
                luaBinaryBuilder.build()));

    py2LibBuilder.build(graphBuilder);
    py3LibBuilder.build(graphBuilder);
    py2CxxLibraryBuilder.build(graphBuilder);
    py3CxxLibraryBuilder.build(graphBuilder);
    cxxPythonExtensionBuilder.build(graphBuilder);
    LuaBinary luaBinary = luaBinaryBuilder.build(graphBuilder);

    LuaPackageComponents components = luaBinary.getComponents();
    assertThat(components.getNativeLibraries().keySet(), Matchers.hasItem("libpy2.so"));
    assertThat(
        components.getNativeLibraries().keySet(), Matchers.not(Matchers.hasItem("libpy3.so")));
  }

  @Test
  public void pythonInitIsRuntimeDepForInPlaceBinary() {
    PythonLibraryBuilder pythonLibraryBuilder =
        new PythonLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(
                SourceSortedSet.ofUnnamedSources(
                    ImmutableSortedSet.of(FakeSourcePath.of("foo.py"))));
    LuaBinaryBuilder luaBinaryBuilder =
        new LuaBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setMainModule("hello.world")
            .setPackageStyle(LuaPlatform.PackageStyle.INPLACE)
            .setDeps(ImmutableSortedSet.of(pythonLibraryBuilder.getTarget()));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(pythonLibraryBuilder.build(), luaBinaryBuilder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    pythonLibraryBuilder.build(graphBuilder, filesystem, targetGraph);
    LuaBinary luaBinary = luaBinaryBuilder.build(graphBuilder, filesystem, targetGraph);
    assertThat(
        luaBinary
            .getRuntimeDeps(new SourcePathRuleFinder(graphBuilder))
            .collect(ImmutableSet.toImmutableSet()),
        Matchers.hasItem(PythonBinaryDescription.getEmptyInitTarget(luaBinary.getBuildTarget())));
  }

  @Test
  public void transitiveNativeDepsUsingMergedNativeLinkStrategy() {
    CxxLibraryBuilder transitiveCxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("transitive_dep.c"))));
    CxxLibraryBuilder cxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("dep.c"))))
            .setDeps(ImmutableSortedSet.of(transitiveCxxDepBuilder.getTarget()));
    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxx"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("cxx.c"))))
            .setDeps(ImmutableSortedSet.of(cxxDepBuilder.getTarget()));

    LuaBinaryBuilder binaryBuilder =
        new LuaBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            LuaTestUtils.DEFAULT_PLATFORM.withNativeLinkStrategy(NativeLinkStrategy.MERGED));
    binaryBuilder.setMainModule("main");
    binaryBuilder.setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()));

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(
                transitiveCxxDepBuilder.build(),
                cxxDepBuilder.build(),
                cxxBuilder.build(),
                binaryBuilder.build()));
    transitiveCxxDepBuilder.build(graphBuilder);
    cxxDepBuilder.build(graphBuilder);
    cxxBuilder.build(graphBuilder);
    LuaBinary binary = binaryBuilder.build(graphBuilder);
    assertThat(
        Iterables.transform(binary.getComponents().getNativeLibraries().keySet(), Object::toString),
        Matchers.containsInAnyOrder("libomnibus.so", "libcxx.so"));
  }

  @Test
  public void transitiveNativeDepsUsingSeparateNativeLinkStrategy() {
    CxxLibraryBuilder transitiveCxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("transitive_dep.c"))));
    CxxLibraryBuilder cxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("dep.c"))))
            .setDeps(ImmutableSortedSet.of(transitiveCxxDepBuilder.getTarget()));
    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxx"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("cxx.c"))))
            .setDeps(ImmutableSortedSet.of(cxxDepBuilder.getTarget()));

    LuaBinaryBuilder binaryBuilder =
        new LuaBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            LuaTestUtils.DEFAULT_PLATFORM.withNativeLinkStrategy(NativeLinkStrategy.SEPARATE));
    binaryBuilder.setMainModule("main");
    binaryBuilder.setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()));

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(
                transitiveCxxDepBuilder.build(),
                cxxDepBuilder.build(),
                cxxBuilder.build(),
                binaryBuilder.build()));
    transitiveCxxDepBuilder.build(graphBuilder);
    cxxDepBuilder.build(graphBuilder);
    cxxBuilder.build(graphBuilder);
    LuaBinary binary = binaryBuilder.build(graphBuilder);
    assertThat(
        Iterables.transform(binary.getComponents().getNativeLibraries().keySet(), Object::toString),
        Matchers.containsInAnyOrder("libtransitive_dep.so", "libdep.so", "libcxx.so"));
  }

  @Test
  public void transitiveDepsOfNativeStarterDepsAreIncludedInMergedNativeLinkStrategy() {
    CxxLibraryBuilder transitiveCxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("transitive_dep.c"))));
    CxxLibraryBuilder cxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("dep.c"))))
            .setDeps(ImmutableSortedSet.of(transitiveCxxDepBuilder.getTarget()));
    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxx"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("cxx.c"))))
            .setDeps(ImmutableSortedSet.of(cxxDepBuilder.getTarget()));
    CxxLibraryBuilder nativeStarterCxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:native_starter"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("native_starter.c"))))
            .setDeps(ImmutableSortedSet.of(transitiveCxxDepBuilder.getTarget()));

    LuaBinaryBuilder binaryBuilder =
        new LuaBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            LuaTestUtils.DEFAULT_PLATFORM.withNativeLinkStrategy(NativeLinkStrategy.MERGED));
    binaryBuilder.setMainModule("main");
    binaryBuilder.setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()));
    binaryBuilder.setNativeStarterLibrary(nativeStarterCxxBuilder.getTarget());

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(
                transitiveCxxDepBuilder.build(),
                cxxDepBuilder.build(),
                cxxBuilder.build(),
                nativeStarterCxxBuilder.build(),
                binaryBuilder.build()));
    transitiveCxxDepBuilder.build(graphBuilder);
    cxxDepBuilder.build(graphBuilder);
    cxxBuilder.build(graphBuilder);
    nativeStarterCxxBuilder.build(graphBuilder);
    LuaBinary binary = binaryBuilder.build(graphBuilder);
    assertThat(
        Iterables.transform(binary.getComponents().getNativeLibraries().keySet(), Object::toString),
        Matchers.containsInAnyOrder("libomnibus.so", "libcxx.so"));
  }

  @Test
  public void pythonExtensionDepUsingMergedNativeLinkStrategy() {
    FlavorDomain<PythonPlatform> pythonPlatforms = FlavorDomain.of("Python Platform", PY2);

    PrebuiltCxxLibraryBuilder python2Builder =
        new PrebuiltCxxLibraryBuilder(PYTHON2_DEP_TARGET)
            .setProvided(true)
            .setSharedLib(FakeSourcePath.of("lipython2.so"));

    CxxPythonExtensionBuilder extensionBuilder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:extension"),
            pythonPlatforms,
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    extensionBuilder.setBaseModule("hello");

    LuaPlatform platform =
        LuaTestUtils.DEFAULT_PLATFORM.withNativeLinkStrategy(NativeLinkStrategy.MERGED);
    LuaBinaryBuilder binaryBuilder =
        new LuaBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            platform,
            FlavorDomain.of(LuaPlatform.FLAVOR_DOMAIN_NAME, platform),
            CxxPlatformUtils.DEFAULT_CONFIG,
            pythonPlatforms);
    binaryBuilder.setMainModule("main");
    binaryBuilder.setDeps(ImmutableSortedSet.of(extensionBuilder.getTarget()));

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            python2Builder.build(), extensionBuilder.build(), binaryBuilder.build());
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    python2Builder.build(graphBuilder, filesystem, targetGraph);
    extensionBuilder.build(graphBuilder, filesystem, targetGraph);
    LuaBinary binary = binaryBuilder.build(graphBuilder, filesystem, targetGraph);
    assertThat(binary.getComponents().getNativeLibraries().entrySet(), Matchers.empty());
    assertThat(
        Iterables.transform(binary.getComponents().getPythonModules().keySet(), Object::toString),
        Matchers.hasItem(MorePaths.pathWithPlatformSeparators("hello/extension.so")));
  }

  @Test
  public void platformDeps() {
    SourcePath libASrc = FakeSourcePath.of("libA.lua");
    LuaLibraryBuilder libraryABuilder =
        new LuaLibraryBuilder(BuildTargetFactory.newInstance("//:libA"))
            .setSrcs(ImmutableSortedSet.of(libASrc));
    SourcePath libBSrc = FakeSourcePath.of("libB.lua");
    LuaLibraryBuilder libraryBBuilder =
        new LuaLibraryBuilder(BuildTargetFactory.newInstance("//:libB"))
            .setSrcs(ImmutableSortedSet.of(libBSrc));
    LuaBinaryBuilder binaryBuilder =
        new LuaBinaryBuilder(BuildTargetFactory.newInstance("//:bin"))
            .setMainModule("main")
            .setPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(
                            CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor().toString(),
                            Pattern.LITERAL),
                        ImmutableSortedSet.of(libraryABuilder.getTarget()))
                    .add(
                        Pattern.compile("matches nothing", Pattern.LITERAL),
                        ImmutableSortedSet.of(libraryBBuilder.getTarget()))
                    .build());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            libraryABuilder.build(), libraryBBuilder.build(), binaryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    LuaBinary binary = (LuaBinary) graphBuilder.requireRule(binaryBuilder.getTarget());
    assertThat(
        binary.getComponents().getModules().values(),
        Matchers.allOf(Matchers.hasItem(libASrc), Matchers.not(Matchers.hasItem(libBSrc))));
  }
}
