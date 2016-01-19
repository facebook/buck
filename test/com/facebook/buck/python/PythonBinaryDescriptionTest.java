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

package com.facebook.buck.python;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBinaryBuilder;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.PrebuiltCxxLibraryBuilder;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class PythonBinaryDescriptionTest {

  private static final BuildTarget PYTHON2_DEP_TARGET =
      BuildTargetFactory.newInstance("//:python2_dep");
  private static final PythonPlatform PY2 =
      PythonPlatform.of(
          ImmutableFlavor.of("py2"),
          new PythonEnvironment(Paths.get("python2"), PythonVersion.of("2.6")),
          Optional.of(PYTHON2_DEP_TARGET));

  @Test
  public void thatComponentSourcePathDepsPropagateProperly() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    Genrule genrule =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen"))
            .setOut("blah.py")
            .build(resolver);
    PythonLibrary lib =
        (PythonLibrary) new PythonLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setSrcs(
                SourceList.ofUnnamedSources(
                    ImmutableSortedSet.<SourcePath>of(
                        new BuildTargetSourcePath(genrule.getBuildTarget()))))
            .build(resolver);
    PythonBinary binary =
        (PythonBinary) PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setMainModule("main")
            .setDeps(ImmutableSortedSet.of(lib.getBuildTarget()))
            .build(resolver);
    assertThat(binary.getDeps(), Matchers.hasItem(genrule));
  }

  @Test
  public void thatMainSourcePathPropagatesToDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    Genrule genrule =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen"))
            .setOut("blah.py")
            .build(resolver);
    PythonBinary binary =
        (PythonBinary) PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setMain(new BuildTargetSourcePath(genrule.getBuildTarget()))
            .build(resolver);
    assertThat(binary.getDeps(), Matchers.hasItem(genrule));
  }

  @Test
  public void baseModule() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    String sourceName = "main.py";
    SourcePath source = new FakeSourcePath("foo/" + sourceName);

    // Run without a base module set and verify it defaults to using the build target
    // base name.
    PythonBinary normal =
        (PythonBinary) PythonBinaryBuilder.create(target)
            .setMain(source)
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new BuildTargetNodeToBuildRuleTransformer()));
    assertThat(
        normal.getComponents().getModules().keySet(),
        Matchers.hasItem(target.getBasePath().resolve(sourceName)));

    // Run *with* a base module set and verify it gets used to build the main module path.
    String baseModule = "blah";
    PythonBinary withBaseModule =
        (PythonBinary) PythonBinaryBuilder.create(target)
            .setMain(source)
            .setBaseModule(baseModule)
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new BuildTargetNodeToBuildRuleTransformer()));
    assertThat(
        withBaseModule.getComponents().getModules().keySet(),
        Matchers.hasItem(Paths.get(baseModule).resolve(sourceName)));
  }

  @Test
  public void mainModule() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    String mainModule = "foo.main";
    PythonBinary binary =
        (PythonBinary) PythonBinaryBuilder.create(target)
            .setMainModule(mainModule)
            .build(resolver);
    assertThat(mainModule, Matchers.equalTo(binary.getMainModule()));
  }

  @Test
  public void pexExtension() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder().setSections(
                ImmutableMap.of(
                    "python",
                    ImmutableMap.of("pex_extension", ".different_extension"))).build(),
            new AlwaysFoundExecutableFinder());
    PythonBinaryBuilder builder =
        new PythonBinaryBuilder(
            target,
            config,
            PythonTestUtils.PYTHON_PLATFORMS,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    PythonBinary binary =
        (PythonBinary) builder
            .setMainModule("main")
            .build(resolver);
    assertThat(
        Preconditions.checkNotNull(binary.getPathToOutput()).toString(),
        Matchers.endsWith(".different_extension"));
  }

  @Test
  public void buildArgs() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    ImmutableList<String> buildArgs = ImmutableList.of("--some", "--args");
    PythonBinary binary =
        (PythonBinary) PythonBinaryBuilder.create(target)
            .setMainModule("main")
            .setBuildArgs(buildArgs)
            .build(resolver);
    ImmutableList<Step> buildSteps =
        binary.getBuildSteps(FakeBuildContext.NOOP_CONTEXT, new FakeBuildableContext());
    PexStep pexStep = FluentIterable.from(buildSteps)
        .filter(PexStep.class)
        .get(0);
    assertThat(
        pexStep.getCommandPrefix(),
        Matchers.hasItems(buildArgs.toArray(new String[buildArgs.size()])));
  }

  @Test
  public void explicitPythonHome() throws Exception {
    PythonPlatform platform1 =
        PythonPlatform.of(
            ImmutableFlavor.of("pyPlat1"),
            new PythonEnvironment(Paths.get("python2.6"), PythonVersion.of("2.6")),
            Optional.<BuildTarget>absent());
    PythonPlatform platform2 =
        PythonPlatform.of(
            ImmutableFlavor.of("pyPlat2"),
            new PythonEnvironment(Paths.get("python2.7"), PythonVersion.of("2.7")),
            Optional.<BuildTarget>absent());
    PythonBinaryBuilder builder =
        PythonBinaryBuilder.create(
            BuildTargetFactory.newInstance("//:bin"),
            new FlavorDomain<>(
                "Python Platform",
                ImmutableMap.of(
                    platform1.getFlavor(), platform1,
                    platform2.getFlavor(), platform2)));
    builder.setMainModule("main");
    PythonBinary binary1 =
        (PythonBinary) builder
            .setPlatform(platform1.getFlavor().toString())
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new BuildTargetNodeToBuildRuleTransformer()));
    assertThat(binary1.getPythonPlatform(), Matchers.equalTo(platform1));
    PythonBinary binary2 =
        (PythonBinary) builder
            .setPlatform(platform2.getFlavor().toString())
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new BuildTargetNodeToBuildRuleTransformer()));
    assertThat(binary2.getPythonPlatform(), Matchers.equalTo(platform2));
  }

  @Test
  public void runtimeDepOnDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    BuildRule cxxBinary =
        new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver);
    BuildRule pythonLibrary =
        new PythonLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setDeps(ImmutableSortedSet.of(cxxBinary.getBuildTarget()))
            .build(resolver);
    PythonBinary pythonBinary =
        (PythonBinary) PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setMainModule("main")
            .setDeps(ImmutableSortedSet.of(pythonLibrary.getBuildTarget()))
            .build(resolver);
    assertThat(
        BuildRules.getTransitiveRuntimeDeps(pythonBinary),
        Matchers.hasItem(cxxBinary));
  }

  @Test
  public void executableCommandWithPathToPexExecutor() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    final Path executor = Paths.get("executor");
    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.builder().build(), new AlwaysFoundExecutableFinder()) {
          @Override
          public Optional<Tool> getPathToPexExecuter(BuildRuleResolver resolver) {
            return Optional.<Tool>of(new HashedFileTool(executor));
          }
        };
    PythonBinaryBuilder builder =
        new PythonBinaryBuilder(
            target,
            config,
            PythonTestUtils.PYTHON_PLATFORMS,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    PythonPackagedBinary binary =
        (PythonPackagedBinary) builder
            .setMainModule("main")
            .build(resolver);
    assertThat(
        binary.getExecutableCommand().getCommandPrefix(pathResolver),
        Matchers.contains(
            executor.toString(),
            binary.getBinPath().toAbsolutePath().toString()));
  }

  @Test
  public void executableCommandWithNoPathToPexExecutor() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    PythonPackagedBinary binary =
        (PythonPackagedBinary) PythonBinaryBuilder.create(target)
            .setMainModule("main")
        .build(resolver);
    assertThat(
        binary.getExecutableCommand().getCommandPrefix(pathResolver),
        Matchers.contains(
            PythonTestUtils.PYTHON_PLATFORM.getEnvironment().getPythonPath().toString(),
            binary.getBinPath().toAbsolutePath().toString()));
  }

  @Test
  public void packagedBinaryAttachedPexToolDeps() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bin");
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    final Genrule pexTool =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:pex_tool"))
            .setOut("pex-tool")
            .build(resolver);
    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.builder().build(), new AlwaysFoundExecutableFinder()) {
          @Override
          public PackageStyle getPackageStyle() {
            return PackageStyle.STANDALONE;
          }
          @Override
          public Tool getPexTool(BuildRuleResolver resolver) {
            return new CommandTool.Builder()
                .addArg(new BuildTargetSourcePath(pexTool.getBuildTarget()))
                .build();
          }
        };
    PythonBinaryBuilder builder =
        new PythonBinaryBuilder(
            target,
            config,
            PythonTestUtils.PYTHON_PLATFORMS,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    PythonPackagedBinary binary =
        (PythonPackagedBinary) builder
            .setMainModule("main")
            .build(resolver);
    assertThat(
        binary.getDeps(),
        Matchers.hasItem(pexTool));
  }

  @Test
  public void transitiveNativeDepsUsingMergedNativeLinkStrategy() throws Exception {
    CxxLibraryBuilder transitiveCxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("transitive_dep.c"))));
    CxxLibraryBuilder cxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("dep.c"))))
            .setDeps(ImmutableSortedSet.of(transitiveCxxDepBuilder.getTarget()));
    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxx"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("cxx.c"))))
            .setDeps(ImmutableSortedSet.of(cxxDepBuilder.getTarget()));

    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.builder().build(), new AlwaysFoundExecutableFinder()) {
          @Override
          public NativeLinkStrategy getNativeLinkStrategy() {
            return NativeLinkStrategy.MERGED;
          }
        };
    PythonBinaryBuilder binaryBuilder =
        new PythonBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            config,
            PythonTestUtils.PYTHON_PLATFORMS,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    binaryBuilder.setMainModule("main");
    binaryBuilder.setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                transitiveCxxDepBuilder.build(),
                cxxDepBuilder.build(),
                cxxBuilder.build(),
                binaryBuilder.build()),
            new BuildTargetNodeToBuildRuleTransformer());
    transitiveCxxDepBuilder.build(resolver);
    cxxDepBuilder.build(resolver);
    cxxBuilder.build(resolver);
    PythonBinary binary = (PythonBinary) binaryBuilder.build(resolver);
    assertThat(
        Iterables.transform(
            binary.getComponents().getNativeLibraries().keySet(),
            Functions.toStringFunction()),
        Matchers.containsInAnyOrder("libomnibus.so", "libcxx.so"));
  }

  @Test
  public void transitiveNativeDepsUsingSeparateNativeLinkStrategy() throws Exception {
    CxxLibraryBuilder transitiveCxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("transitive_dep.c"))));
    CxxLibraryBuilder cxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("dep.c"))))
            .setDeps(ImmutableSortedSet.of(transitiveCxxDepBuilder.getTarget()));
    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxx"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("cxx.c"))))
            .setDeps(ImmutableSortedSet.of(cxxDepBuilder.getTarget()));

    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.builder().build(), new AlwaysFoundExecutableFinder()) {
          @Override
          public NativeLinkStrategy getNativeLinkStrategy() {
            return NativeLinkStrategy.SEPARATE;
          }
        };
    PythonBinaryBuilder binaryBuilder =
        new PythonBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            config,
            PythonTestUtils.PYTHON_PLATFORMS,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    binaryBuilder.setMainModule("main");
    binaryBuilder.setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                transitiveCxxDepBuilder.build(),
                cxxDepBuilder.build(),
                cxxBuilder.build(),
                binaryBuilder.build()),
            new BuildTargetNodeToBuildRuleTransformer());
    transitiveCxxDepBuilder.build(resolver);
    cxxDepBuilder.build(resolver);
    cxxBuilder.build(resolver);
    PythonBinary binary = (PythonBinary) binaryBuilder.build(resolver);
    assertThat(
        Iterables.transform(
            binary.getComponents().getNativeLibraries().keySet(),
            Functions.toStringFunction()),
        Matchers.containsInAnyOrder("libtransitive_dep.so", "libdep.so", "libcxx.so"));
  }

  @Test
  public void extensionDepUsingMergedNativeLinkStrategy() throws Exception {
    FlavorDomain<PythonPlatform> pythonPlatforms =
        new FlavorDomain<>(
            "Python Platform",
            ImmutableMap.of(PY2.getFlavor(), PY2));

    PrebuiltCxxLibraryBuilder python2Builder =
        new PrebuiltCxxLibraryBuilder(PYTHON2_DEP_TARGET)
            .setHeaderOnly(true)
            .setExportedLinkerFlags(ImmutableList.of("-lpython2"));

    CxxPythonExtensionBuilder extensionBuilder =
        new CxxPythonExtensionBuilder(
            BuildTargetFactory.newInstance("//:extension"),
            pythonPlatforms,
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    extensionBuilder.setBaseModule("hello");

    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.builder().build(), new AlwaysFoundExecutableFinder()) {
          @Override
          public NativeLinkStrategy getNativeLinkStrategy() {
            return NativeLinkStrategy.MERGED;
          }
        };
    PythonBinaryBuilder binaryBuilder =
        new PythonBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            config,
            pythonPlatforms,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    binaryBuilder.setMainModule("main");
    binaryBuilder.setDeps(ImmutableSortedSet.of(extensionBuilder.getTarget()));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                python2Builder.build(),
                extensionBuilder.build(),
                binaryBuilder.build()),
            new BuildTargetNodeToBuildRuleTransformer());
    python2Builder.build(resolver);
    extensionBuilder.build(resolver);
    PythonBinary binary = (PythonBinary) binaryBuilder.build(resolver);
    assertThat(
        binary.getComponents().getNativeLibraries().entrySet(),
        Matchers.<Map.Entry<Path, SourcePath>>empty());
    assertThat(
        Iterables.transform(
            binary.getComponents().getModules().keySet(),
            Functions.toStringFunction()),
        Matchers.containsInAnyOrder("hello/extension.so"));
  }

  @Test
  public void transitiveDepsOfLibsWithPrebuiltNativeLibsAreNotIncludedInMergedLink()
      throws Exception {
    CxxLibraryBuilder transitiveCxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_cxx"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("transitive_cxx.c"))));
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxx"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("cxx.c"))))
            .setDeps(ImmutableSortedSet.of(transitiveCxxLibraryBuilder.getTarget()));
    PythonLibraryBuilder pythonLibraryBuilder =
        new PythonLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setSrcs(
                SourceList.ofUnnamedSources(
                    ImmutableSortedSet.<SourcePath>of(new FakeSourcePath("prebuilt.so"))))
            .setDeps(ImmutableSortedSet.of(cxxLibraryBuilder.getTarget()));
    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.builder().build(), new AlwaysFoundExecutableFinder()) {
          @Override
          public NativeLinkStrategy getNativeLinkStrategy() {
            return NativeLinkStrategy.MERGED;
          }
        };
    PythonBinaryBuilder pythonBinaryBuilder =
        new PythonBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            config,
            PythonTestUtils.PYTHON_PLATFORMS,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            CxxPlatformUtils.DEFAULT_PLATFORMS);
    pythonBinaryBuilder.setMainModule("main");
    pythonBinaryBuilder.setDeps(ImmutableSortedSet.of(pythonLibraryBuilder.getTarget()));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                transitiveCxxLibraryBuilder.build(),
                cxxLibraryBuilder.build(),
                pythonLibraryBuilder.build(),
                pythonBinaryBuilder.build()),
            new BuildTargetNodeToBuildRuleTransformer());
    transitiveCxxLibraryBuilder.build(resolver);
    cxxLibraryBuilder.build(resolver);
    pythonLibraryBuilder.build(resolver);
    PythonBinary binary = (PythonBinary) pythonBinaryBuilder.build(resolver);
    assertThat(
        Iterables.transform(
            binary.getComponents().getNativeLibraries().keySet(),
            Functions.toStringFunction()),
        Matchers.hasItem("libtransitive_cxx.so"));
  }

  @Test
  public void packageStyleParam() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    PythonBinary pythonBinary =
        (PythonBinary) PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setMainModule("main")
            .setPackageStyle(PythonBuckConfig.PackageStyle.INPLACE)
            .build(resolver);
    assertThat(
        pythonBinary,
        Matchers.instanceOf(PythonInPlaceBinary.class));
    resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    pythonBinary =
        (PythonBinary) PythonBinaryBuilder.create(BuildTargetFactory.newInstance("//:bin"))
            .setMainModule("main")
            .setPackageStyle(PythonBuckConfig.PackageStyle.STANDALONE)
            .build(resolver);
    assertThat(
        pythonBinary,
        Matchers.instanceOf(PythonPackagedBinary.class));
  }

}
