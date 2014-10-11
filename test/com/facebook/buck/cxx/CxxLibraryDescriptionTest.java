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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

public class CxxLibraryDescriptionTest {

  private GenruleDescription.Arg createEmptyGenruleDescriptionArg() {
    GenruleDescription.Arg arg = new GenruleDescription().createUnpopulatedConstructorArg();
    arg.bash = Optional.absent();
    arg.cmd = Optional.absent();
    arg.cmdExe = Optional.absent();
    arg.deps = Optional.absent();
    arg.srcs = Optional.absent();
    arg.out = "";
    return arg;
  }

  private <T> TargetNode<?> createTargetNode(
      BuildTarget target,
      Description<T> description,
      T arg) {
    BuildRuleFactoryParams params = new BuildRuleFactoryParams(
        new FakeProjectFilesystem(),
        new BuildTargetParser(),
        target,
        new FakeRuleKeyBuilderFactory());
    try {
      return new TargetNode<>(
          description,
          arg,
          params,
          ImmutableSet.<BuildTarget>of(),
          ImmutableSet.<BuildTargetPattern>of());
    } catch (NoSuchBuildTargetException e) {
      throw Throwables.propagate(e);
    }
  }

  private <T> TargetGraph createTargetGraph(
      BuildTarget target,
      Description<T> description,
      T arg) {
    return TargetGraphFactory.newInstance(createTargetNode(target, description, arg));
  }

  @Test
  @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
  public void createBuildRule() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    // Setup a genrule the generates a header we'll list.
    String genHeaderName = "test/foo.h";
    GenruleDescription genHeaderDescription = new GenruleDescription();
    GenruleDescription.Arg genHeaderArg = createEmptyGenruleDescriptionArg();
    genHeaderArg.out = genHeaderName;
    BuildTarget genHeaderTarget = BuildTargetFactory.newInstance("//:genHeader");
    Genrule genHeader = (Genrule) GenruleBuilder
        .newGenruleBuilder(genHeaderTarget)
        .setOut(genHeaderName)
        .build(resolver);

    // Setup a genrule the generates a source we'll list.
    String genSourceName = "test/foo.cpp";
    GenruleDescription genSourceDescription = new GenruleDescription();
    GenruleDescription.Arg genSourceArg = createEmptyGenruleDescriptionArg();
    genHeaderArg.out = genSourceName;
    BuildTarget genSourceTarget = BuildTargetFactory.newInstance("//:genSource");
    Genrule genSource = (Genrule) GenruleBuilder
        .newGenruleBuilder(genSourceTarget)
        .setOut(genSourceName)
        .build(resolver);

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    final BuildRule header = new FakeBuildRule("//:header", pathResolver);
    final BuildRule headerSymlinkTree = new FakeBuildRule("//:symlink", pathResolver);
    final Path headerSymlinkTreeRoot = Paths.get("symlink/tree/root");
    final BuildRule archive = new FakeBuildRule("//:archive", pathResolver);
    final Path archiveOutput = Paths.get("output/path/lib.a");
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    BuildRuleParams depParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(depTarget);
    AbstractCxxLibrary dep = new AbstractCxxLibrary(depParams, pathResolver) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput() {
        return CxxPreprocessorInput.builder()
            .setRules(
                ImmutableSet.of(
                    header.getBuildTarget(),
                    headerSymlinkTree.getBuildTarget()))
            .setIncludeRoots(headerSymlinkTreeRoot)
            .build();
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(Linker linker, Type type) {
        return new NativeLinkableInput(
            ImmutableList.<SourcePath>of(new BuildTargetSourcePath(archive.getBuildTarget())),
            ImmutableList.of(archiveOutput.toString()));
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents() {
        return new PythonPackageComponents(
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of());
      }

    };
    resolver.addAllToIndex(ImmutableList.of(header, headerSymlinkTree, archive));

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");

    // Instantiate a description and call its `createBuildRule` method.
    DefaultCxxPlatform cxxBuckConfig = new DefaultCxxPlatform(new FakeBuckConfig());
    CxxLibraryDescription description = new CxxLibraryDescription(cxxBuckConfig);
    CxxLibraryDescription.Arg arg = description.createEmptyConstructorArg();
    arg.deps = Optional.of(ImmutableSortedSet.of(dep.getBuildTarget()));
    arg.srcs =
        Optional.of(
            Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofLeft(
                ImmutableList.<SourcePath>of(
                    new TestSourcePath("test/bar.cpp"),
                    new BuildTargetSourcePath(genSource.getBuildTarget()))));
    String headerName = "test/bar.h";
    arg.headers =
        Optional.of(
            Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofLeft(
                ImmutableList.<SourcePath>of(
                    new TestSourcePath(headerName),
                    new BuildTargetSourcePath(genHeader.getBuildTarget()))));
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target)
        .setTargetGraph(
            TargetGraphFactory.newInstance(
                createTargetNode(target, description, arg),
                createTargetNode(genSource.getBuildTarget(), genSourceDescription, genSourceArg),
                createTargetNode(genHeader.getBuildTarget(), genHeaderDescription, genHeaderArg),
                createTargetNode(
                    depTarget,
                    new GenruleDescription(),
                    createEmptyGenruleDescriptionArg())))
        .setDeps(ImmutableSortedSet.<BuildRule>of(dep))
        .build();
    CxxLibrary rule = (CxxLibrary) description.createBuildRule(params, resolver, arg);

    assertEquals(
        CxxPreprocessorInput.builder()
            .setRules(
                ImmutableSet.of(
                    CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target)))
            .setIncludes(
                ImmutableMap.<Path, SourcePath>of(
                    Paths.get(headerName),
                    new TestSourcePath(headerName),
                    Paths.get(genHeaderName),
                    new BuildTargetSourcePath(genHeader.getBuildTarget())))
            .setIncludeRoots(
                ImmutableList.of(
                    CxxDescriptionEnhancer.getHeaderSymlinkTreePath(target)))
            .build(),
        rule.getCxxPreprocessorInput());

    // Verify that the archive rule has the correct deps: the object files from our sources.
    rule.getNativeLinkableInput(cxxBuckConfig.getLd(), NativeLinkable.Type.STATIC);
    BuildRule archiveRule = resolver.getRule(
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(target));
    assertNotNull(archiveRule);
    assertEquals(
        ImmutableSet.of(
            CxxCompilableEnhancer.createCompileBuildTarget(
                target,
                "test/bar.cpp",
                /* pic */ false),
            CxxCompilableEnhancer.createCompileBuildTarget(
                target,
                genSourceName,
                /* pic */ false)),
        FluentIterable.from(archiveRule.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule compileRule1 = resolver.getRule(
        CxxCompilableEnhancer.createCompileBuildTarget(
            target,
            "test/bar.cpp",
            /* pic */ false));
    assertNotNull(compileRule1);
    assertEquals(
        ImmutableSet.of(
            genHeaderTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target)),
        FluentIterable.from(compileRule1.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule compileRule2 = resolver.getRule(
        CxxCompilableEnhancer.createCompileBuildTarget(
            target,
            genSourceName,
            /* pic */ false));
    assertNotNull(compileRule2);
    assertEquals(
        ImmutableSet.of(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target)),
        FluentIterable.from(compileRule2.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());
  }

  @Test
  public void overrideSoname() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    String soname = "test_soname";

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");

    // Instantiate a description and call its `createBuildRule` method.
    DefaultCxxPlatform cxxBuckConfig = new DefaultCxxPlatform(new FakeBuckConfig());
    CxxLibraryDescription description = new CxxLibraryDescription(cxxBuckConfig);
    CxxLibraryDescription.Arg arg = description.createEmptyConstructorArg();
    arg.soname = Optional.of(soname);
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target)
        .setTargetGraph(createTargetGraph(target, description, arg))
        .build();
    CxxLibrary rule = (CxxLibrary) description.createBuildRule(params, resolver, arg);

    Linker linker = cxxBuckConfig.getLd();
    NativeLinkableInput input = rule.getNativeLinkableInput(
        linker,
        NativeLinkable.Type.SHARED);

    ImmutableList<SourcePath> inputs = input.getInputs();
    assertEquals(inputs.size(), 1);
    SourcePath sourcePath = inputs.get(0);
    assertTrue(sourcePath instanceof BuildTargetSourcePath);
    BuildRule buildRule = new SourcePathResolver(resolver).getRule(sourcePath).get();
    assertTrue(buildRule instanceof CxxLink);
    CxxLink cxxLink = (CxxLink) buildRule;
    ImmutableList<String> args = cxxLink.getArgs();
    assertNotEquals(
        -1,
        Collections.indexOfSubList(
            args,
            ImmutableList.copyOf(CxxLinkableEnhancer.iXlinker(linker.soname(soname)))));
  }

  @Test
  public void linkWhole() {
    BuildRuleResolver resolver = new BuildRuleResolver();

    FakeBuckConfig buckConfig = new FakeBuckConfig();
    DefaultCxxPlatform cxxBuckConfig = new DefaultCxxPlatform(buckConfig);
    Linker linker = new GnuLinker(new TestSourcePath("linker"));
    CxxLibraryDescription description = new CxxLibraryDescription(cxxBuckConfig);

    // Setup the target name and build params.
    BuildTarget target = BuildTargetFactory.newInstance("//:test");

    // First, create a cxx library without using link whole.
    CxxLibraryDescription.Arg normalArg = description.createEmptyConstructorArg();
    BuildRuleParams normalParams = new FakeBuildRuleParamsBuilder(target)
        .setTargetGraph(createTargetGraph(target, description, normalArg))
        .build();
    CxxLibrary normal = (CxxLibrary) description.createBuildRule(normalParams, resolver, normalArg);

    // Verify that the linker args contains the link whole flags.
    assertFalse(
        normal.getNativeLinkableInput(
            linker, NativeLinkable.Type.STATIC).getArgs().contains("--whole-archive"));
    assertFalse(
        normal.getNativeLinkableInput(
            linker, NativeLinkable.Type.STATIC).getArgs().contains("--no-whole-archive"));

    // Create a cxx library using link whole.
    CxxLibraryDescription.Arg linkWholeArg = description.createEmptyConstructorArg();
    linkWholeArg.linkWhole = Optional.of(true);
    BuildRuleParams linkWholeParams = new FakeBuildRuleParamsBuilder(target)
        .setTargetGraph(createTargetGraph(target, description, linkWholeArg))
        .build();
    CxxLibrary linkWhole =
        (CxxLibrary) description.createBuildRule(linkWholeParams, resolver, linkWholeArg);

    // Verify that the linker args contains the link whole flags.
    assertTrue(
        linkWhole.getNativeLinkableInput(
            linker, NativeLinkable.Type.STATIC).getArgs().contains("--whole-archive"));
    assertTrue(
        linkWhole.getNativeLinkableInput(
            linker, NativeLinkable.Type.STATIC).getArgs().contains("--no-whole-archive"));
  }

  @Test
  @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
  public void createCxxLibraryBuildRules() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    // Setup a normal C++ source
    String sourceName = "test/bar.cpp";

    // Setup a genrule the generates a header we'll list.
    String genHeaderName = "test/foo.h";
    GenruleDescription genHeaderDescription = new GenruleDescription();
    GenruleDescription.Arg genHeaderArg = createEmptyGenruleDescriptionArg();
    genHeaderArg.out = genHeaderName;
    BuildTarget genHeaderTarget = BuildTargetFactory.newInstance("//:genHeader");
    Genrule genHeader = (Genrule) GenruleBuilder
        .newGenruleBuilder(genHeaderTarget)
        .setOut(genHeaderName)
        .build(resolver);

    // Setup a genrule the generates a source we'll list.
    String genSourceName = "test/foo.cpp";
    GenruleDescription genSourceDescription = new GenruleDescription();
    GenruleDescription.Arg genSourceArg = createEmptyGenruleDescriptionArg();
    genHeaderArg.out = genSourceName;
    BuildTarget genSourceTarget = BuildTargetFactory.newInstance("//:genSource");
    Genrule genSource = (Genrule) GenruleBuilder
        .newGenruleBuilder(genSourceTarget)
        .setOut(genSourceName)
        .build(resolver);

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    final BuildRule header = new FakeBuildRule("//:header", pathResolver);
    final BuildRule headerSymlinkTree = new FakeBuildRule("//:symlink", pathResolver);
    final Path headerSymlinkTreeRoot = Paths.get("symlink/tree/root");
    final BuildRule staticLibraryDep = new FakeBuildRule("//:static", pathResolver);
    final Path staticLibraryOutput = Paths.get("output/path/lib.a");
    final BuildRule sharedLibraryDep = new FakeBuildRule("//:shared", pathResolver);
    final Path sharedLibraryOutput = Paths.get("output/path/lib.so");
    final String sharedLibrarySoname = "soname";
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    BuildRuleParams depParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(depTarget);
    AbstractCxxLibrary dep = new AbstractCxxLibrary(depParams, pathResolver) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput() {
        return CxxPreprocessorInput.builder()
            .setRules(
                ImmutableSet.of(
                    header.getBuildTarget(),
                    headerSymlinkTree.getBuildTarget()))
            .setIncludeRoots(headerSymlinkTreeRoot)
            .build();
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(Linker linker, Type type) {
        return type == Type.STATIC ?
            new NativeLinkableInput(
                ImmutableList.<SourcePath>of(
                    new BuildTargetSourcePath(staticLibraryDep.getBuildTarget())),
                ImmutableList.of(staticLibraryOutput.toString())) :
            new NativeLinkableInput(
                ImmutableList.<SourcePath>of(
                    new BuildTargetSourcePath(sharedLibraryDep.getBuildTarget())),
                ImmutableList.of(sharedLibraryOutput.toString()));
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents() {
        return new PythonPackageComponents(
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(sharedLibrarySoname), new PathSourcePath(sharedLibraryOutput)));
      }

    };
    resolver.addAllToIndex(
        ImmutableList.of(
            header,
            headerSymlinkTree,
            staticLibraryDep,
            sharedLibraryDep));

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");

    // Construct C/C++ library build rules.
    DefaultCxxPlatform cxxBuckConfig = new DefaultCxxPlatform(new FakeBuckConfig());
    CxxLibraryDescription description = new CxxLibraryDescription(cxxBuckConfig);
    CxxLibraryDescription.Arg arg = description.createEmptyConstructorArg();
    arg.headers =
        Optional.of(
            Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofRight(
                ImmutableMap.<String, SourcePath>of(
                    genHeaderName, new BuildTargetSourcePath(genHeader.getBuildTarget()))));
    arg.srcs =
        Optional.of(
            Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofRight(
                ImmutableMap.<String, SourcePath>of(
                    sourceName, new TestSourcePath(sourceName),
                    genSourceName, new BuildTargetSourcePath(genSource.getBuildTarget()))));
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target)
        .setTargetGraph(
            TargetGraphFactory.newInstance(
                createTargetNode(target, description, arg),
                createTargetNode(genSource.getBuildTarget(), genSourceDescription, genSourceArg),
                createTargetNode(genHeader.getBuildTarget(), genHeaderDescription, genHeaderArg)))
        .setDeps(ImmutableSortedSet.<BuildRule>of(dep))
        .build();
    CxxLibrary rule = (CxxLibrary) description.createBuildRule(params, resolver, arg);

    // Verify the C/C++ preprocessor input is setup correctly.
    assertEquals(
        CxxPreprocessorInput.builder()
            .setRules(
                ImmutableSet.of(
                    CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target)))
            .setIncludes(
                ImmutableMap.<Path, SourcePath>of(
                    Paths.get(genHeaderName),
                    new BuildTargetSourcePath(genHeader.getBuildTarget())))
            .setIncludeRoots(
                ImmutableList.of(
                    CxxDescriptionEnhancer.getHeaderSymlinkTreePath(target)))
            .build(),
        rule.getCxxPreprocessorInput());

    // Verify that the archive rule has the correct deps: the object files from our sources.
    rule.getNativeLinkableInput(cxxBuckConfig.getLd(), NativeLinkable.Type.STATIC);
    BuildRule staticRule = resolver.getRule(
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(target));
    assertNotNull(staticRule);
    assertEquals(
        ImmutableSet.of(
            CxxCompilableEnhancer.createCompileBuildTarget(
                target,
                "test/bar.cpp",
                /* pic */ false),
            CxxCompilableEnhancer.createCompileBuildTarget(
                target,
                genSourceName,
                /* pic */ false)),
        FluentIterable.from(staticRule.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule staticCompileRule1 = resolver.getRule(
        CxxCompilableEnhancer.createCompileBuildTarget(
            target,
            "test/bar.cpp",
            /* pic */ false));
    assertNotNull(staticCompileRule1);
    assertEquals(
        ImmutableSet.of(
            genHeaderTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target)),
        FluentIterable.from(staticCompileRule1.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule staticCompileRule2 = resolver.getRule(
        CxxCompilableEnhancer.createCompileBuildTarget(
            target,
            genSourceName,
            /* pic */ false));
    assertNotNull(staticCompileRule2);
    assertEquals(
        ImmutableSet.of(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target)),
        FluentIterable.from(staticCompileRule2.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the archive rule has the correct deps: the object files from our sources.
    rule.getNativeLinkableInput(cxxBuckConfig.getLd(), NativeLinkable.Type.SHARED);
    BuildRule sharedRule = resolver.getRule(
        CxxDescriptionEnhancer.createSharedLibraryBuildTarget(target));
    assertNotNull(sharedRule);
    assertEquals(
        ImmutableSet.of(
            sharedLibraryDep.getBuildTarget(),
            CxxCompilableEnhancer.createCompileBuildTarget(
                target,
                "test/bar.cpp",
                /* pic */ true),
            CxxCompilableEnhancer.createCompileBuildTarget(
                target,
                genSourceName,
                /* pic */ true)),
        FluentIterable.from(sharedRule.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule sharedCompileRule1 = resolver.getRule(
        CxxCompilableEnhancer.createCompileBuildTarget(
            target,
            "test/bar.cpp",
            /* pic */ true));
    assertNotNull(sharedCompileRule1);
    assertEquals(
        ImmutableSet.of(
            genHeaderTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target)),
        FluentIterable.from(sharedCompileRule1.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule sharedCompileRule2 = resolver.getRule(
        CxxCompilableEnhancer.createCompileBuildTarget(
            target,
            genSourceName,
            /* pic */ true));
    assertNotNull(sharedCompileRule2);
    assertEquals(
        ImmutableSet.of(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target)),
        FluentIterable.from(sharedCompileRule2.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Check the python interface returning by this C++ library.
    PythonPackageComponents expectedPythonPackageComponents = new PythonPackageComponents(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(
            Paths.get(CxxDescriptionEnhancer.getSharedLibrarySoname(target)),
            new BuildTargetSourcePath(sharedRule.getBuildTarget())));
    assertEquals(
        expectedPythonPackageComponents,
        rule.getPythonPackageComponents());
  }

}
