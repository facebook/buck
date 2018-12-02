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

package com.facebook.buck.features.python;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.cxx.CxxGenrule;
import com.facebook.buck.cxx.CxxGenruleBuilder;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.FixedVersionSelector;
import com.facebook.buck.versions.ParallelVersionedTargetGraphBuilder;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionedAliasBuilder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class PythonLibraryDescriptionTest {

  @Test
  public void baseModule() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    String sourceName = "main.py";
    SourcePath source = FakeSourcePath.of("foo/" + sourceName);

    // Run without a base module set and verify it defaults to using the build target
    // base name.
    PythonLibraryBuilder normalBuilder =
        new PythonLibraryBuilder(target)
            .setSrcs(SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of(source)));
    TargetGraph normalTargetGraph = TargetGraphFactory.newInstance(normalBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(normalTargetGraph);
    PythonLibrary normal = normalBuilder.build(graphBuilder, filesystem, normalTargetGraph);
    assertEquals(
        ImmutableMap.of(target.getBasePath().resolve(sourceName), source),
        normal
            .getPythonPackageComponents(
                PythonTestUtils.PYTHON_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)
            .getModules());

    // Run *with* a base module set and verify it gets used to build the main module path.
    String baseModule = "blah";
    PythonLibraryBuilder withBaseModuleBuilder =
        new PythonLibraryBuilder(target)
            .setSrcs(SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of(source)))
            .setBaseModule(baseModule);
    TargetGraph withBaseModuleTargetGraph =
        TargetGraphFactory.newInstance(withBaseModuleBuilder.build());
    graphBuilder = new TestActionGraphBuilder(withBaseModuleTargetGraph);
    PythonLibrary withBaseModule =
        withBaseModuleBuilder.build(graphBuilder, filesystem, withBaseModuleTargetGraph);
    assertEquals(
        ImmutableMap.of(Paths.get(baseModule).resolve(sourceName), source),
        withBaseModule
            .getPythonPackageComponents(
                PythonTestUtils.PYTHON_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)
            .getModules());
  }

  @Test
  public void platformSrcs() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    SourcePath pyPlatformMatchedSource = FakeSourcePath.of("foo/a.py");
    SourcePath cxxPlatformMatchedSource = FakeSourcePath.of("foo/b.py");
    SourcePath unmatchedSource = FakeSourcePath.of("foo/c.py");
    PythonLibraryBuilder builder =
        new PythonLibraryBuilder(target)
            .setPlatformSrcs(
                PatternMatchedCollection.<SourceSortedSet>builder()
                    .add(
                        Pattern.compile("^" + PythonTestUtils.PYTHON_PLATFORM.getFlavor() + "$"),
                        SourceSortedSet.ofUnnamedSources(
                            ImmutableSortedSet.of(pyPlatformMatchedSource)))
                    .add(
                        Pattern.compile("^" + CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor() + "$"),
                        SourceSortedSet.ofUnnamedSources(
                            ImmutableSortedSet.of(cxxPlatformMatchedSource)))
                    .add(
                        Pattern.compile("won't match anything"),
                        SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    PythonLibrary library = builder.build(graphBuilder, filesystem, targetGraph);
    assertThat(
        library
            .getPythonPackageComponents(
                PythonTestUtils.PYTHON_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)
            .getModules()
            .values(),
        Matchers.contains(pyPlatformMatchedSource, cxxPlatformMatchedSource));
  }

  @Test
  public void platformResources() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    SourcePath pyPlatformMatchedSource = FakeSourcePath.of("foo/a.dat");
    SourcePath cxxPlatformMatchedSource = FakeSourcePath.of("foo/b.dat");
    SourcePath unmatchedSource = FakeSourcePath.of("foo/c.dat");
    PythonLibraryBuilder builder =
        new PythonLibraryBuilder(target)
            .setPlatformResources(
                PatternMatchedCollection.<SourceSortedSet>builder()
                    .add(
                        Pattern.compile("^" + PythonTestUtils.PYTHON_PLATFORM.getFlavor() + "$"),
                        SourceSortedSet.ofUnnamedSources(
                            ImmutableSortedSet.of(pyPlatformMatchedSource)))
                    .add(
                        Pattern.compile("^" + CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor() + "$"),
                        SourceSortedSet.ofUnnamedSources(
                            ImmutableSortedSet.of(cxxPlatformMatchedSource)))
                    .add(
                        Pattern.compile("won't match anything"),
                        SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    PythonLibrary library = builder.build(graphBuilder, filesystem, targetGraph);
    assertThat(
        library
            .getPythonPackageComponents(
                PythonTestUtils.PYTHON_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)
            .getResources()
            .values(),
        Matchers.contains(pyPlatformMatchedSource, cxxPlatformMatchedSource));
  }

  @Test
  public void versionedSrcs() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    SourcePath matchedSource = FakeSourcePath.of("foo/a.py");
    SourcePath unmatchedSource = FakeSourcePath.of("foo/b.py");
    GenruleBuilder transitiveDepBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:tdep")).setOut("out");
    VersionedAliasBuilder depBuilder =
        new VersionedAliasBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setVersions(
                ImmutableMap.of(
                    Version.of("1.0"), transitiveDepBuilder.getTarget(),
                    Version.of("2.0"), transitiveDepBuilder.getTarget()));
    PythonLibraryBuilder builder =
        new PythonLibraryBuilder(target)
            .setVersionedSrcs(
                VersionMatchedCollection.<SourceSortedSet>builder()
                    .add(
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")),
                        SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("2.0")),
                        SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build());
    TargetGraph targetGraph =
        ParallelVersionedTargetGraphBuilder.transform(
                new FixedVersionSelector(
                    ImmutableMap.of(
                        builder.getTarget(),
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")))),
                TargetGraphAndBuildTargets.of(
                    TargetGraphFactory.newInstance(
                        transitiveDepBuilder.build(), depBuilder.build(), builder.build()),
                    ImmutableSet.of(builder.getTarget())),
                new ForkJoinPool(),
                new DefaultTypeCoercerFactory(),
                20)
            .getTargetGraph();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    PythonLibrary library = (PythonLibrary) graphBuilder.requireRule(builder.getTarget());
    assertThat(
        library
            .getPythonPackageComponents(
                PythonTestUtils.PYTHON_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)
            .getModules()
            .values(),
        Matchers.contains(matchedSource));
  }

  @Test
  public void versionedResources() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    SourcePath matchedSource = FakeSourcePath.of("foo/a.py");
    SourcePath unmatchedSource = FakeSourcePath.of("foo/b.py");
    GenruleBuilder transitiveDepBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:tdep")).setOut("out");
    VersionedAliasBuilder depBuilder =
        new VersionedAliasBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setVersions(
                ImmutableMap.of(
                    Version.of("1.0"), transitiveDepBuilder.getTarget(),
                    Version.of("2.0"), transitiveDepBuilder.getTarget()));
    PythonLibraryBuilder builder =
        new PythonLibraryBuilder(target)
            .setVersionedResources(
                VersionMatchedCollection.<SourceSortedSet>builder()
                    .add(
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")),
                        SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("2.0")),
                        SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build());
    TargetGraph targetGraph =
        ParallelVersionedTargetGraphBuilder.transform(
                new FixedVersionSelector(
                    ImmutableMap.of(
                        builder.getTarget(),
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")))),
                TargetGraphAndBuildTargets.of(
                    TargetGraphFactory.newInstance(
                        transitiveDepBuilder.build(), depBuilder.build(), builder.build()),
                    ImmutableSet.of(builder.getTarget())),
                new ForkJoinPool(),
                new DefaultTypeCoercerFactory(),
                20)
            .getTargetGraph();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    PythonLibrary library = (PythonLibrary) graphBuilder.requireRule(builder.getTarget());
    assertThat(
        library
            .getPythonPackageComponents(
                PythonTestUtils.PYTHON_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)
            .getResources()
            .values(),
        Matchers.contains(matchedSource));
  }

  @Test
  public void cxxGenruleSrcs() {
    CxxGenruleBuilder srcBuilder =
        new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:src")).setOut("out.py");
    PythonLibraryBuilder libraryBuilder =
        new PythonLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setSrcs(
                SourceSortedSet.ofUnnamedSources(
                    ImmutableSortedSet.of(
                        DefaultBuildTargetSourcePath.of(srcBuilder.getTarget()))));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(srcBuilder.build(), libraryBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    CxxGenrule src = (CxxGenrule) graphBuilder.requireRule(srcBuilder.getTarget());
    PythonLibrary library = (PythonLibrary) graphBuilder.requireRule(libraryBuilder.getTarget());
    PythonPackageComponents components =
        library.getPythonPackageComponents(
            PythonTestUtils.PYTHON_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder);
    assertThat(
        components.getModules().values(),
        Matchers.contains(src.getGenrule(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder)));
  }

  @Test
  public void platformDeps() {
    PythonLibraryBuilder libraryABuilder =
        PythonLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:libA"));
    PythonLibraryBuilder libraryBBuilder =
        PythonLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:libB"));
    PythonLibraryBuilder ruleBuilder =
        PythonLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:rule"))
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
            libraryABuilder.build(), libraryBBuilder.build(), ruleBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    PythonLibrary rule = (PythonLibrary) graphBuilder.requireRule(ruleBuilder.getTarget());
    assertThat(
        RichStream.from(
                rule.getPythonPackageDeps(
                    PythonTestUtils.PYTHON_PLATFORM,
                    CxxPlatformUtils.DEFAULT_PLATFORM,
                    graphBuilder))
            .map(BuildRule::getBuildTarget)
            .toImmutableSet(),
        Matchers.allOf(
            Matchers.hasItem(libraryABuilder.getTarget()),
            Matchers.not(Matchers.hasItem(libraryBBuilder.getTarget()))));
  }

  @Test
  public void excludingTransitiveNativeDepsUsingMergedNativeLinkStrategy() {
    CxxLibraryBuilder cxxDepBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("dep.c"))));
    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxx"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("cxx.c"))))
            .setDeps(ImmutableSortedSet.of(cxxDepBuilder.getTarget()));
    PythonLibraryBuilder libBuilder =
        new PythonLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()))
            .setExcludeDepsFromMergedLinking(true);

    PythonBuckConfig config =
        new PythonBuckConfig(FakeBuckConfig.builder().build()) {
          @Override
          public NativeLinkStrategy getNativeLinkStrategy() {
            return NativeLinkStrategy.MERGED;
          }
        };
    PythonBinaryBuilder binaryBuilder =
        PythonBinaryBuilder.create(
            BuildTargetFactory.newInstance("//:bin"), config, PythonTestUtils.PYTHON_PLATFORMS);
    binaryBuilder.setMainModule("main");
    binaryBuilder.setDeps(ImmutableSortedSet.of(libBuilder.getTarget()));

    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(
                cxxDepBuilder.build(),
                cxxBuilder.build(),
                libBuilder.build(),
                binaryBuilder.build()));
    cxxDepBuilder.build(graphBuilder);
    cxxBuilder.build(graphBuilder);
    libBuilder.build(graphBuilder);
    PythonBinary binary = binaryBuilder.build(graphBuilder);
    assertThat(
        Iterables.transform(binary.getComponents().getNativeLibraries().keySet(), Object::toString),
        Matchers.containsInAnyOrder("libdep.so", "libcxx.so"));
  }
}
