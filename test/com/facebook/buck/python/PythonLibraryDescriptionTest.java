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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.CxxGenrule;
import com.facebook.buck.cxx.CxxGenruleBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.FixedVersionSelector;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionedAliasBuilder;
import com.facebook.buck.versions.VersionedTargetGraphBuilder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
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
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(source)));
    TargetGraph normalTargetGraph = TargetGraphFactory.newInstance(normalBuilder.build());
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver(normalTargetGraph);
    PythonLibrary normal = normalBuilder.build(ruleResolver, filesystem, normalTargetGraph);
    assertEquals(
        ImmutableMap.of(target.getBasePath().resolve(sourceName), source),
        normal
            .getPythonPackageComponents(
                PythonTestUtils.PYTHON_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORM, ruleResolver)
            .getModules());

    // Run *with* a base module set and verify it gets used to build the main module path.
    String baseModule = "blah";
    PythonLibraryBuilder withBaseModuleBuilder =
        new PythonLibraryBuilder(target)
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(source)))
            .setBaseModule(baseModule);
    TargetGraph withBaseModuleTargetGraph =
        TargetGraphFactory.newInstance(withBaseModuleBuilder.build());
    ruleResolver = new TestBuildRuleResolver(withBaseModuleTargetGraph);
    PythonLibrary withBaseModule =
        withBaseModuleBuilder.build(ruleResolver, filesystem, withBaseModuleTargetGraph);
    assertEquals(
        ImmutableMap.of(Paths.get(baseModule).resolve(sourceName), source),
        withBaseModule
            .getPythonPackageComponents(
                PythonTestUtils.PYTHON_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORM, ruleResolver)
            .getModules());
  }

  @Test
  public void platformSrcs() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    SourcePath matchedSource = FakeSourcePath.of("foo/a.py");
    SourcePath unmatchedSource = FakeSourcePath.of("foo/b.py");
    PythonLibraryBuilder builder =
        new PythonLibraryBuilder(target)
            .setPlatformSrcs(
                PatternMatchedCollection.<SourceList>builder()
                    .add(
                        Pattern.compile(PythonTestUtils.PYTHON_PLATFORM.getFlavor().toString()),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        Pattern.compile("won't match anything"),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver(targetGraph);
    PythonLibrary library = builder.build(ruleResolver, filesystem, targetGraph);
    assertThat(
        library
            .getPythonPackageComponents(
                PythonTestUtils.PYTHON_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORM, ruleResolver)
            .getModules()
            .values(),
        Matchers.contains(matchedSource));
  }

  @Test
  public void platformResources() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    SourcePath matchedSource = FakeSourcePath.of("foo/a.dat");
    SourcePath unmatchedSource = FakeSourcePath.of("foo/b.dat");
    PythonLibraryBuilder builder =
        new PythonLibraryBuilder(target)
            .setPlatformResources(
                PatternMatchedCollection.<SourceList>builder()
                    .add(
                        Pattern.compile(PythonTestUtils.PYTHON_PLATFORM.getFlavor().toString()),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        Pattern.compile("won't match anything"),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver(targetGraph);
    PythonLibrary library = builder.build(ruleResolver, filesystem, targetGraph);
    assertThat(
        library
            .getPythonPackageComponents(
                PythonTestUtils.PYTHON_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORM, ruleResolver)
            .getResources()
            .values(),
        Matchers.contains(matchedSource));
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
                VersionMatchedCollection.<SourceList>builder()
                    .add(
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("2.0")),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build());
    TargetGraph targetGraph =
        VersionedTargetGraphBuilder.transform(
                new FixedVersionSelector(
                    ImmutableMap.of(
                        builder.getTarget(),
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")))),
                TargetGraphAndBuildTargets.of(
                    TargetGraphFactory.newInstance(
                        transitiveDepBuilder.build(), depBuilder.build(), builder.build()),
                    ImmutableSet.of(builder.getTarget())),
                new ForkJoinPool(),
                new DefaultTypeCoercerFactory())
            .getTargetGraph();
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PythonLibrary library = (PythonLibrary) resolver.requireRule(builder.getTarget());
    assertThat(
        library
            .getPythonPackageComponents(
                PythonTestUtils.PYTHON_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORM, resolver)
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
                VersionMatchedCollection.<SourceList>builder()
                    .add(
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("2.0")),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build());
    TargetGraph targetGraph =
        VersionedTargetGraphBuilder.transform(
                new FixedVersionSelector(
                    ImmutableMap.of(
                        builder.getTarget(),
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")))),
                TargetGraphAndBuildTargets.of(
                    TargetGraphFactory.newInstance(
                        transitiveDepBuilder.build(), depBuilder.build(), builder.build()),
                    ImmutableSet.of(builder.getTarget())),
                new ForkJoinPool(),
                new DefaultTypeCoercerFactory())
            .getTargetGraph();
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PythonLibrary library = (PythonLibrary) resolver.requireRule(builder.getTarget());
    assertThat(
        library
            .getPythonPackageComponents(
                PythonTestUtils.PYTHON_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORM, resolver)
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
                SourceList.ofUnnamedSources(
                    ImmutableSortedSet.of(
                        DefaultBuildTargetSourcePath.of(srcBuilder.getTarget()))));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(srcBuilder.build(), libraryBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    CxxGenrule src = (CxxGenrule) resolver.requireRule(srcBuilder.getTarget());
    PythonLibrary library = (PythonLibrary) resolver.requireRule(libraryBuilder.getTarget());
    PythonPackageComponents components =
        library.getPythonPackageComponents(
            PythonTestUtils.PYTHON_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORM, resolver);
    assertThat(
        components.getModules().values(),
        Matchers.contains(src.getGenrule(CxxPlatformUtils.DEFAULT_PLATFORM, resolver)));
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
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    PythonLibrary rule = (PythonLibrary) resolver.requireRule(ruleBuilder.getTarget());
    assertThat(
        RichStream.from(
                rule.getPythonPackageDeps(
                    PythonTestUtils.PYTHON_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORM, resolver))
            .map(BuildRule::getBuildTarget)
            .toImmutableSet(),
        Matchers.allOf(
            Matchers.hasItem(libraryABuilder.getTarget()),
            Matchers.not(Matchers.hasItem(libraryBBuilder.getTarget()))));
  }
}
