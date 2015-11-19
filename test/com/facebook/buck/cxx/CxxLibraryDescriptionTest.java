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

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.python.PythonPlatform;
import com.facebook.buck.python.PythonTestUtils;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.shell.ExportFile;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

public class CxxLibraryDescriptionTest {

  private static Path getHeaderSymlinkTreeIncludePath(
      BuildTarget target,
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
    if (cxxPlatform.getCpp().supportsHeaderMaps() && cxxPlatform.getCxxpp().supportsHeaderMaps()) {
      return BuckConstant.BUCK_OUTPUT_PATH;
    } else {
      return CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
          target,
          cxxPlatform.getFlavor(),
          headerVisibility);
    }
  }

  private static ImmutableSet<Path> getHeaderMaps(
      BuildTarget target,
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
    if (cxxPlatform.getCpp().supportsHeaderMaps() && cxxPlatform.getCxxpp().supportsHeaderMaps()) {
      return ImmutableSet.of(
          CxxDescriptionEnhancer.getHeaderMapPath(
              target,
              cxxPlatform.getFlavor(),
              headerVisibility));
    } else {
      return ImmutableSet.of();
    }
  }

  @Test
  @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
  public void createBuildRule() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    CxxPlatform cxxPlatform = CxxLibraryBuilder.createDefaultPlatform();

    // Setup a genrule the generates a header we'll list.
    String genHeaderName = "test/foo.h";
    BuildTarget genHeaderTarget = BuildTargetFactory.newInstance("//:genHeader");
    GenruleBuilder genHeaderBuilder = GenruleBuilder
        .newGenruleBuilder(genHeaderTarget)
        .setOut(genHeaderName);
    genHeaderBuilder.build(resolver);

    // Setup a genrule the generates a source we'll list.
    String genSourceName = "test/foo.cpp";
    BuildTarget genSourceTarget = BuildTargetFactory.newInstance("//:genSource");
    GenruleBuilder genSourceBuilder = GenruleBuilder
        .newGenruleBuilder(genSourceTarget)
        .setOut(genSourceName);
    genSourceBuilder.build(resolver);

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    final BuildRule header = new FakeBuildRule("//:header", pathResolver);
    final BuildRule headerSymlinkTree = new FakeBuildRule("//:symlink", pathResolver);
    final Path headerSymlinkTreeRoot = Paths.get("symlink/tree/root");

    final BuildRule privateHeader = new FakeBuildRule("//:header-private", pathResolver);
    final BuildRule privateHeaderSymlinkTree = new FakeBuildRule(
        "//:symlink-private", pathResolver);
    final Path privateHeaderSymlinkTreeRoot = Paths.get("private/symlink/tree/root");

    final BuildRule archive = new FakeBuildRule("//:archive", pathResolver);
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    BuildRuleParams depParams = new FakeBuildRuleParamsBuilder(depTarget).build();
    AbstractCxxLibrary dep = new AbstractCxxLibrary(depParams, pathResolver) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput(
          TargetGraph targetGraph,
          CxxPlatform cxxPlatform,
          HeaderVisibility headerVisibility) {
        switch (headerVisibility) {
          case PUBLIC:
            return CxxPreprocessorInput.builder()
                .addRules(
                    header.getBuildTarget(),
                    headerSymlinkTree.getBuildTarget())
                .addIncludeRoots(headerSymlinkTreeRoot)
                .build();
          case PRIVATE:
            return CxxPreprocessorInput.builder()
                .addRules(
                    privateHeader.getBuildTarget(),
                    privateHeaderSymlinkTree.getBuildTarget())
                .addIncludeRoots(privateHeaderSymlinkTreeRoot)
                .build();
        }
        throw new RuntimeException("Invalid header visibility: " + headerVisibility);
      }

      @Override
      public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
          TargetGraph targetGraph,
          CxxPlatform cxxPlatform,
          HeaderVisibility headerVisibility) {
        return ImmutableMap.of(
            getBuildTarget(),
            getCxxPreprocessorInput(targetGraph, cxxPlatform, headerVisibility));
      }

      @Override
      public Iterable<NativeLinkable> getNativeLinkableDeps(CxxPlatform cxxPlatform) {
        return FluentIterable.from(getDeclaredDeps())
            .filter(NativeLinkable.class);
      }

      @Override
      public Iterable<NativeLinkable> getNativeLinkableExportedDeps(CxxPlatform cxxPlatform) {
        return FluentIterable.from(getDeclaredDeps())
            .filter(NativeLinkable.class);
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(
          TargetGraph targetGraph,
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType type) {
        return NativeLinkableInput.of(
            ImmutableList.<Arg>of(
                new SourcePathArg(
                    getResolver(),
                    new BuildTargetSourcePath(archive.getBuildTarget()))),
            ImmutableSet.<FrameworkPath>of(),
            ImmutableSet.<FrameworkPath>of());
      }

      @Override
      public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
        return Linkage.ANY;
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents(
          TargetGraph targetGraph,
          PythonPlatform pythonPlatform,
          CxxPlatform cxxPlatform) {
        return PythonPackageComponents.of(
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableSet.<SourcePath>of(),
            Optional.<Boolean>absent());
      }

      @Override
      public Iterable<AndroidPackageable> getRequiredPackageables() {
        return ImmutableList.of();
      }

      @Override
      public void addToCollector(AndroidPackageableCollector collector) {}

      @Override
      public ImmutableMap<String, SourcePath> getSharedLibraries(
          TargetGraph targetGraph,
          CxxPlatform cxxPlatform) {
        return ImmutableMap.of();
      }

      @Override
      public boolean isTestedBy(BuildTarget buildTarget) {
        return false;
      }
    };
    resolver.addAllToIndex(
        ImmutableList.of(
            header, headerSymlinkTree, privateHeader, privateHeaderSymlinkTree, archive, dep));

    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(
        filesystem.getRootPath(),
        target,
        cxxPlatform);
    String headerName = "test/bar.h";
    String privateHeaderName = "test/bar_private.h";
    CxxLibraryBuilder cxxLibraryBuilder = (CxxLibraryBuilder) new CxxLibraryBuilder(target)
        .setExportedHeaders(
            ImmutableSortedSet.<SourcePath>of(
                new FakeSourcePath(headerName),
                new BuildTargetSourcePath(genHeaderTarget)))
        .setHeaders(
            ImmutableSortedSet.<SourcePath>of(new FakeSourcePath(privateHeaderName)))
        .setSrcs(
            ImmutableSortedSet.of(
                SourceWithFlags.of(new FakeSourcePath("test/bar.cpp")),
                SourceWithFlags.of(new BuildTargetSourcePath(genSourceTarget))))
        .setFrameworks(
            ImmutableSortedSet.of(
                FrameworkPath.ofSourcePath(new FakeSourcePath("/some/framework/path/s.dylib")),
                FrameworkPath.ofSourcePath(new FakeSourcePath("/another/framework/path/a.dylib"))))
        .setDeps(ImmutableSortedSet.of(dep.getBuildTarget()));

    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        cxxLibraryBuilder.build(),
        genSourceBuilder.build(),
        genHeaderBuilder.build(),
        GenruleBuilder.newGenruleBuilder(depTarget).build());
    CxxLibrary rule = (CxxLibrary) cxxLibraryBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);

    Path headerRoot =
        CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
            target,
            cxxPlatform.getFlavor(),
            HeaderVisibility.PUBLIC);
    assertEquals(
        CxxPreprocessorInput.builder()
            .addRules(
                CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                    target,
                    cxxPlatform.getFlavor(),
                    HeaderVisibility.PUBLIC))
            .setIncludes(
                CxxHeaders.builder()
                    .putNameToPathMap(
                        Paths.get(headerName),
                        new FakeSourcePath(headerName))
                    .putNameToPathMap(
                        Paths.get(genHeaderName),
                        new BuildTargetSourcePath(genHeaderTarget))
                    .putFullNameToPathMap(
                        headerRoot.resolve(headerName),
                        new FakeSourcePath(headerName))
                    .putFullNameToPathMap(
                        headerRoot.resolve(genHeaderName),
                        new BuildTargetSourcePath(genHeaderTarget))
                    .build())
            .addIncludeRoots(
                getHeaderSymlinkTreeIncludePath(
                    target,
                    cxxPlatform,
                    HeaderVisibility.PUBLIC))
            .addAllHeaderMaps(
                getHeaderMaps(
                    target,
                    cxxPlatform,
                    HeaderVisibility.PUBLIC))
            .addFrameworks(
                FrameworkPath.ofSourcePath(
                    new PathSourcePath(filesystem, Paths.get("/some/framework/path/s.dylib"))),
                FrameworkPath.ofSourcePath(
                    new PathSourcePath(filesystem, Paths.get("/another/framework/path/a.dylib"))))
            .build(),
        rule.getCxxPreprocessorInput(
            targetGraph,
            cxxPlatform,
            HeaderVisibility.PUBLIC));

    Path privateHeaderRoot =
        CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
            target,
            cxxPlatform.getFlavor(),
            HeaderVisibility.PRIVATE);
    assertEquals(
        CxxPreprocessorInput.builder()
            .addRules(
                CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                    target,
                    cxxPlatform.getFlavor(),
                    HeaderVisibility.PRIVATE))
            .setIncludes(
                CxxHeaders.builder()
                    .putNameToPathMap(
                        Paths.get(privateHeaderName),
                        new FakeSourcePath(privateHeaderName))
                    .putFullNameToPathMap(
                        privateHeaderRoot.resolve(privateHeaderName),
                        new FakeSourcePath(privateHeaderName))
                    .build())
            .addIncludeRoots(
                getHeaderSymlinkTreeIncludePath(
                    target,
                    cxxPlatform,
                    HeaderVisibility.PRIVATE))
            .addAllHeaderMaps(
                getHeaderMaps(
                    target,
                    cxxPlatform,
                    HeaderVisibility.PRIVATE))
            .addFrameworks(
                FrameworkPath.ofSourcePath(
                    new PathSourcePath(filesystem, Paths.get("/some/framework/path/s.dylib"))),
                FrameworkPath.ofSourcePath(
                    new PathSourcePath(filesystem, Paths.get("/another/framework/path/a.dylib"))))
            .build(),
        rule.getCxxPreprocessorInput(
            targetGraph,
            cxxPlatform,
            HeaderVisibility.PRIVATE));

    // Verify that the archive rule has the correct deps: the object files from our sources.
    rule.getNativeLinkableInput(targetGraph, cxxPlatform, Linker.LinkableDepType.STATIC);
    BuildRule archiveRule = resolver.getRule(
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            target,
            cxxPlatform.getFlavor(),
            CxxSourceRuleFactory.PicType.PDC));
    assertNotNull(archiveRule);
    assertEquals(
        ImmutableSet.of(
            cxxSourceRuleFactory.createCompileBuildTarget(
                "test/bar.cpp",
                CxxSourceRuleFactory.PicType.PDC),
            cxxSourceRuleFactory.createCompileBuildTarget(
                genSourceName,
                CxxSourceRuleFactory.PicType.PDC)),
        FluentIterable.from(archiveRule.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the preprocess rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule preprocessRule1 = resolver.getRule(
        cxxSourceRuleFactory.createPreprocessBuildTarget(
            "test/bar.cpp",
            CxxSource.Type.CXX,
            CxxSourceRuleFactory.PicType.PDC));
    assertEquals(
        ImmutableSet.of(
            genHeaderTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PRIVATE),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PUBLIC)),
        FluentIterable.from(preprocessRule1.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule compileRule1 = resolver.getRule(
        cxxSourceRuleFactory.createCompileBuildTarget(
            "test/bar.cpp",
            CxxSourceRuleFactory.PicType.PDC));
    assertNotNull(compileRule1);
    assertEquals(
        ImmutableSet.of(
            preprocessRule1.getBuildTarget()),
        FluentIterable.from(compileRule1.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the preprocess rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule preprocessRule2 = resolver.getRule(
        cxxSourceRuleFactory.createPreprocessBuildTarget(
            genSourceName,
            CxxSource.Type.CXX,
            CxxSourceRuleFactory.PicType.PDC));
    assertEquals(
        ImmutableSet.of(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PRIVATE),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PUBLIC)),
        FluentIterable.from(preprocessRule2.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule compileRule2 = resolver.getRule(
        cxxSourceRuleFactory.createCompileBuildTarget(
            genSourceName,
            CxxSourceRuleFactory.PicType.PDC));
    assertNotNull(compileRule2);
    assertEquals(
        ImmutableSet.of(
            preprocessRule2.getBuildTarget()),
        FluentIterable.from(compileRule2.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());
  }

  @Test
  public void overrideSoname() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform cxxPlatform = CxxLibraryBuilder.createDefaultPlatform();

    String soname = "test_soname";

    // Generate the C++ library rules.
    BuildTarget target =
        BuildTargetFactory.newInstance(
            String.format("//:rule#shared,%s", CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor()));
    AbstractCxxSourceBuilder<CxxLibraryDescription.Arg> ruleBuilder = new CxxLibraryBuilder(target)
        .setSoname(soname)
        .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.cpp"))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(ruleBuilder.build());
    CxxLink rule = (CxxLink) ruleBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);
    Linker linker = cxxPlatform.getLd();
    ImmutableList<String> sonameArgs = ImmutableList.copyOf(linker.soname(soname));
    assertThat(
        rule.getArgs(),
        Matchers.hasItems(sonameArgs.toArray(new String[sonameArgs.size()])));
  }

  @Test
  public void linkWhole() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform cxxPlatform = CxxLibraryBuilder.createDefaultPlatform();

    // Setup the target name and build params.
    BuildTarget target = BuildTargetFactory.newInstance("//:test");

    // Lookup the link whole flags.
    Linker linker = cxxPlatform.getLd();
    ImmutableList<String> linkWholeFlags =
        FluentIterable.from(linker.linkWhole(new StringArg("sentinel")))
            .transform(Arg.stringifyFunction())
            .filter(Predicates.not(Predicates.equalTo("sentinel")))
            .toList();

    // First, create a cxx library without using link whole.
    CxxLibraryBuilder normalBuilder = new CxxLibraryBuilder(target);
    TargetGraph normalGraph = TargetGraphFactory.newInstance(normalBuilder.build());
    CxxLibrary normal = (CxxLibrary) normalBuilder
        .setSrcs(
            ImmutableSortedSet.<SourceWithFlags>of(
                SourceWithFlags.of(new FakeSourcePath("test.cpp"))))
        .build(
            resolver,
            filesystem,
            normalGraph);

    // Verify that the linker args contains the link whole flags.
    NativeLinkableInput input =
        normal.getNativeLinkableInput(
            normalGraph,
            cxxPlatform,
            Linker.LinkableDepType.STATIC);
    assertThat(
        FluentIterable.from(input.getArgs())
            .transform(Arg.stringifyFunction())
            .toList(),
        Matchers.not(Matchers.hasItems(linkWholeFlags.toArray(new String[linkWholeFlags.size()]))));

    // Create a cxx library using link whole.
    AbstractCxxSourceBuilder<CxxLibraryDescription.Arg> linkWholeBuilder =
        new CxxLibraryBuilder(target)
            .setLinkWhole(true)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.cpp"))));

    TargetGraph linkWholeGraph = TargetGraphFactory.newInstance(linkWholeBuilder.build());
    resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    CxxLibrary linkWhole = (CxxLibrary) linkWholeBuilder
        .setSrcs(
            ImmutableSortedSet.<SourceWithFlags>of(
                SourceWithFlags.of(new FakeSourcePath("test.cpp"))))
        .build(
            resolver,
            filesystem,
            linkWholeGraph);

    // Verify that the linker args contains the link whole flags.
    NativeLinkableInput linkWholeInput =
        linkWhole.getNativeLinkableInput(
            normalGraph,
            cxxPlatform,
            Linker.LinkableDepType.STATIC);
    assertThat(
        FluentIterable.from(linkWholeInput.getArgs())
            .transform(Arg.stringifyFunction())
            .toList(),
        Matchers.hasItems(linkWholeFlags.toArray(new String[linkWholeFlags.size()])));
  }

  @Test
  @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
  public void createCxxLibraryBuildRules() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    CxxPlatform cxxPlatform = CxxLibraryBuilder.createDefaultPlatform();

    // Setup a normal C++ source
    String sourceName = "test/bar.cpp";

    // Setup a genrule the generates a header we'll list.
    String genHeaderName = "test/foo.h";
    BuildTarget genHeaderTarget = BuildTargetFactory.newInstance("//:genHeader");
    GenruleBuilder genHeaderBuilder = GenruleBuilder
        .newGenruleBuilder(genHeaderTarget)
        .setOut(genHeaderName);
    genHeaderBuilder.build(resolver);

    // Setup a genrule the generates a source we'll list.
    String genSourceName = "test/foo.cpp";
    BuildTarget genSourceTarget = BuildTargetFactory.newInstance("//:genSource");
    GenruleBuilder genSourceBuilder = GenruleBuilder
        .newGenruleBuilder(genSourceTarget)
        .setOut(genSourceName);
    genSourceBuilder.build(resolver);

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    final BuildRule header = new FakeBuildRule("//:header", pathResolver);
    final BuildRule headerSymlinkTree = new FakeBuildRule("//:symlink", pathResolver);
    final Path headerSymlinkTreeRoot = Paths.get("symlink/tree/root");
    final BuildRule staticLibraryDep = new FakeBuildRule("//:static", pathResolver);
    final BuildRule sharedLibraryDep = new FakeBuildRule("//:shared", pathResolver);
    final Path sharedLibraryOutput = Paths.get("output/path/lib.so");
    final String sharedLibrarySoname = "soname";
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    BuildRuleParams depParams = new FakeBuildRuleParamsBuilder(depTarget).build();
    AbstractCxxLibrary dep = new AbstractCxxLibrary(depParams, pathResolver) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput(
          TargetGraph targetGraph,
          CxxPlatform cxxPlatform,
          HeaderVisibility headerVisibility) {
        return CxxPreprocessorInput.builder()
            .addRules(
                header.getBuildTarget(),
                headerSymlinkTree.getBuildTarget())
            .addIncludeRoots(headerSymlinkTreeRoot)
            .build();
      }

      @Override
      public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
          TargetGraph targetGraph,
          CxxPlatform cxxPlatform,
          HeaderVisibility headerVisibility) {
        return ImmutableMap.of(
            getBuildTarget(),
            getCxxPreprocessorInput(targetGraph, cxxPlatform, headerVisibility));
      }

      @Override
      public Iterable<NativeLinkable> getNativeLinkableDeps(CxxPlatform cxxPlatform) {
        return FluentIterable.from(getDeclaredDeps())
            .filter(NativeLinkable.class);
      }

      @Override
      public Iterable<NativeLinkable> getNativeLinkableExportedDeps(CxxPlatform cxxPlatform) {
        return FluentIterable.from(getDeclaredDeps())
            .filter(NativeLinkable.class);
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(
          TargetGraph targetGraph,
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType type) {
        return type == Linker.LinkableDepType.STATIC ?
            NativeLinkableInput.of(
                ImmutableList.<Arg>of(
                    new SourcePathArg(
                        getResolver(),
                        new BuildTargetSourcePath(
                            staticLibraryDep.getBuildTarget()))),
                ImmutableSet.<FrameworkPath>of(),
                ImmutableSet.<FrameworkPath>of()) :
            NativeLinkableInput.of(
                ImmutableList.<Arg>of(
                    new SourcePathArg(
                        getResolver(),
                        new BuildTargetSourcePath(
                            sharedLibraryDep.getBuildTarget()))),
                ImmutableSet.<FrameworkPath>of(),
                ImmutableSet.<FrameworkPath>of());
      }

      @Override
      public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
        return Linkage.ANY;
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents(
          TargetGraph targetGraph,
          PythonPlatform pythonPlatform,
          CxxPlatform cxxPlatform) {
        return PythonPackageComponents.of(
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(sharedLibrarySoname),
                new PathSourcePath(getProjectFilesystem(), sharedLibraryOutput)),
            ImmutableSet.<SourcePath>of(),
            Optional.<Boolean>absent());
      }

      @Override
      public Iterable<AndroidPackageable> getRequiredPackageables() {
        return ImmutableList.of();
      }

      @Override
      public void addToCollector(AndroidPackageableCollector collector) {}

      @Override
      public ImmutableMap<String, SourcePath> getSharedLibraries(
          TargetGraph targetGraph,
          CxxPlatform cxxPlatform) {
        return ImmutableMap.of();
      }

      @Override
      public boolean isTestedBy(BuildTarget buildTarget) {
        return false;
      }
    };
    resolver.addAllToIndex(
        ImmutableList.of(
            header,
            headerSymlinkTree,
            staticLibraryDep,
            sharedLibraryDep,
            dep));

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(
        filesystem.getRootPath(),
        target,
        cxxPlatform);
    CxxLibraryBuilder cxxLibraryBuilder = (CxxLibraryBuilder) new CxxLibraryBuilder(target)
        .setExportedHeaders(
            ImmutableSortedMap.<String, SourcePath>of(
                genHeaderName, new BuildTargetSourcePath(genHeaderTarget)))
        .setSrcs(
            ImmutableSortedSet.of(
                SourceWithFlags.of(new FakeSourcePath(sourceName)),
                SourceWithFlags.of(new BuildTargetSourcePath(genSourceTarget))))
        .setFrameworks(
            ImmutableSortedSet.of(
                FrameworkPath.ofSourcePath(new FakeSourcePath("/some/framework/path/s.dylib")),
                FrameworkPath.ofSourcePath(new FakeSourcePath("/another/framework/path/a.dylib"))))
        .setDeps(ImmutableSortedSet.of(dep.getBuildTarget()));

    // Construct C/C++ library build rules.
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        cxxLibraryBuilder.build(),
        genSourceBuilder.build(),
        genHeaderBuilder.build(),
        GenruleBuilder.newGenruleBuilder(depTarget)
            .build());
    CxxLibrary rule = (CxxLibrary) cxxLibraryBuilder
        .build(resolver, filesystem, targetGraph);

    // Verify the C/C++ preprocessor input is setup correctly.
    Path headerRoot =
        CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
            target,
            cxxPlatform.getFlavor(),
            HeaderVisibility.PUBLIC);
    assertEquals(
        CxxPreprocessorInput.builder()
            .addRules(
                CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                    target,
                    cxxPlatform.getFlavor(),
                    HeaderVisibility.PUBLIC))
            .setIncludes(
                CxxHeaders.builder()
                    .putNameToPathMap(
                        Paths.get(genHeaderName),
                        new BuildTargetSourcePath(genHeaderTarget))
                    .putFullNameToPathMap(
                        headerRoot.resolve(genHeaderName),
                        new BuildTargetSourcePath(genHeaderTarget))
                    .build())
            .addIncludeRoots(
                getHeaderSymlinkTreeIncludePath(
                    target,
                    cxxPlatform,
                    HeaderVisibility.PUBLIC))
            .addAllHeaderMaps(
                getHeaderMaps(
                    target,
                    cxxPlatform,
                    HeaderVisibility.PUBLIC))
            .addFrameworks(
                FrameworkPath.ofSourcePath(
                    new PathSourcePath(filesystem, Paths.get("/some/framework/path/s.dylib"))),
                FrameworkPath.ofSourcePath(
                    new PathSourcePath(filesystem, Paths.get("/another/framework/path/a.dylib"))))
            .build(),
        rule.getCxxPreprocessorInput(targetGraph, cxxPlatform, HeaderVisibility.PUBLIC));

    // Verify that the archive rule has the correct deps: the object files from our sources.
    rule.getNativeLinkableInput(targetGraph, cxxPlatform, Linker.LinkableDepType.STATIC);
    BuildRule staticRule = resolver.getRule(
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            target,
            cxxPlatform.getFlavor(),
            CxxSourceRuleFactory.PicType.PDC));
    assertNotNull(staticRule);
    assertEquals(
        ImmutableSet.of(
            cxxSourceRuleFactory.createCompileBuildTarget(
                "test/bar.cpp",
                CxxSourceRuleFactory.PicType.PDC),
            cxxSourceRuleFactory.createCompileBuildTarget(
                genSourceName,
                CxxSourceRuleFactory.PicType.PDC)),
        FluentIterable.from(staticRule.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule staticPreprocessRule1 = resolver.getRule(
        cxxSourceRuleFactory.createPreprocessBuildTarget(
            "test/bar.cpp",
            CxxSource.Type.CXX,
            CxxSourceRuleFactory.PicType.PDC));
    assertNotNull(staticPreprocessRule1);
    assertEquals(
        ImmutableSet.of(
            genHeaderTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PRIVATE),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PUBLIC)),
        FluentIterable.from(staticPreprocessRule1.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule staticCompileRule1 = resolver.getRule(
        cxxSourceRuleFactory.createCompileBuildTarget(
            "test/bar.cpp",
            CxxSourceRuleFactory.PicType.PDC));
    assertNotNull(staticCompileRule1);
    assertEquals(
        ImmutableSet.of(staticPreprocessRule1.getBuildTarget()),
        FluentIterable.from(staticCompileRule1.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule staticPreprocessRule2 = resolver.getRule(
        cxxSourceRuleFactory.createPreprocessBuildTarget(
            genSourceName,
            CxxSource.Type.CXX,
            CxxSourceRuleFactory.PicType.PDC));
    assertNotNull(staticPreprocessRule2);
    assertEquals(
        ImmutableSet.of(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PRIVATE),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PUBLIC)),
        FluentIterable.from(staticPreprocessRule2.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule staticCompileRule2 = resolver.getRule(
        cxxSourceRuleFactory.createCompileBuildTarget(
            genSourceName,
            CxxSourceRuleFactory.PicType.PDC));
    assertNotNull(staticCompileRule2);
    assertEquals(
        ImmutableSet.of(staticPreprocessRule2.getBuildTarget()),
        FluentIterable.from(staticCompileRule2.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the archive rule has the correct deps: the object files from our sources.
    rule.getNativeLinkableInput(targetGraph, cxxPlatform, Linker.LinkableDepType.SHARED);
    BuildRule sharedRule = resolver.getRule(
        CxxDescriptionEnhancer.createSharedLibraryBuildTarget(target, cxxPlatform.getFlavor()));
    assertNotNull(sharedRule);
    assertEquals(
        ImmutableSet.of(
            sharedLibraryDep.getBuildTarget(),
            cxxSourceRuleFactory.createCompileBuildTarget(
                "test/bar.cpp",
                CxxSourceRuleFactory.PicType.PIC),
            cxxSourceRuleFactory.createCompileBuildTarget(
                genSourceName,
                CxxSourceRuleFactory.PicType.PIC)),
        FluentIterable.from(sharedRule.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule sharedPreprocessRule1 = resolver.getRule(
        cxxSourceRuleFactory.createPreprocessBuildTarget(
            "test/bar.cpp",
            CxxSource.Type.CXX,
            CxxSourceRuleFactory.PicType.PIC));
    assertNotNull(sharedPreprocessRule1);
    assertEquals(
        ImmutableSet.of(
            genHeaderTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PRIVATE),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PUBLIC)),
        FluentIterable.from(sharedPreprocessRule1.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule sharedCompileRule1 = resolver.getRule(
        cxxSourceRuleFactory.createCompileBuildTarget(
            "test/bar.cpp",
            CxxSourceRuleFactory.PicType.PIC));
    assertNotNull(sharedCompileRule1);
    assertEquals(
        ImmutableSet.of(sharedPreprocessRule1.getBuildTarget()),
        FluentIterable.from(sharedCompileRule1.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule sharedPreprocessRule2 = resolver.getRule(
        cxxSourceRuleFactory.createPreprocessBuildTarget(
            genSourceName,
            CxxSource.Type.CXX,
            CxxSourceRuleFactory.PicType.PIC));
    assertNotNull(sharedPreprocessRule2);
    assertEquals(
        ImmutableSet.of(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PRIVATE),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
                target,
                cxxPlatform.getFlavor(),
                HeaderVisibility.PUBLIC)),
        FluentIterable.from(sharedPreprocessRule2.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule sharedCompileRule2 = resolver.getRule(
        cxxSourceRuleFactory.createCompileBuildTarget(
            genSourceName,
            CxxSourceRuleFactory.PicType.PIC));
    assertNotNull(sharedCompileRule2);
    assertEquals(
        ImmutableSet.of(sharedPreprocessRule2.getBuildTarget()),
        FluentIterable.from(sharedCompileRule2.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Check the python interface returning by this C++ library.
    PythonPackageComponents expectedPythonPackageComponents = PythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(
            Paths.get(
                CxxDescriptionEnhancer.getDefaultSharedLibrarySoname(
                    target,
                    cxxPlatform)),
            new BuildTargetSourcePath(sharedRule.getBuildTarget())),
        ImmutableSet.<SourcePath>of(),
        Optional.<Boolean>absent());
    assertEquals(
        expectedPythonPackageComponents,
        rule.getPythonPackageComponents(targetGraph, PythonTestUtils.PYTHON_PLATFORM, cxxPlatform));
  }

  @Test
  public void supportedPlatforms() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");

    // First, make sure without any platform regex, we get something back for each of the interface
    // methods.
    CxxLibraryBuilder cxxLibraryBuilder =
        (CxxLibraryBuilder) new CxxLibraryBuilder(target)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("test.c"))));
    TargetGraph targetGraph1 = TargetGraphFactory.newInstance(cxxLibraryBuilder.build());
    CxxLibrary cxxLibrary = (CxxLibrary) cxxLibraryBuilder
        .build(
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()),
            filesystem,
            targetGraph1);
    assertThat(
        cxxLibrary.getSharedLibraries(targetGraph1, CxxPlatformUtils.DEFAULT_PLATFORM).entrySet(),
        Matchers.not(empty()));
    assertThat(
        cxxLibrary
            .getPythonPackageComponents(
                targetGraph1,
                PythonTestUtils.PYTHON_PLATFORM,
                CxxPlatformUtils.DEFAULT_PLATFORM)
            .getNativeLibraries()
            .entrySet(),
        Matchers.not(empty()));
    assertThat(
        cxxLibrary
            .getNativeLinkableInput(
                targetGraph1,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Linker.LinkableDepType.SHARED)
            .getArgs(),
        Matchers.not(empty()));

    // Now, verify we get nothing when the supported platform regex excludes our platform.
    cxxLibraryBuilder.setSupportedPlatformsRegex(Pattern.compile("nothing"));
    TargetGraph targetGraph2 = TargetGraphFactory.newInstance(cxxLibraryBuilder.build());
    cxxLibrary = (CxxLibrary) cxxLibraryBuilder
        .build(
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()),
            filesystem,
            targetGraph2);
    assertThat(
        cxxLibrary.getSharedLibraries(targetGraph2, CxxPlatformUtils.DEFAULT_PLATFORM).entrySet(),
        empty());
    assertThat(
        cxxLibrary
            .getPythonPackageComponents(
                targetGraph2,
                PythonTestUtils.PYTHON_PLATFORM,
                CxxPlatformUtils.DEFAULT_PLATFORM)
            .getNativeLibraries()
            .entrySet(),
        empty());
    assertThat(
        cxxLibrary
            .getNativeLinkableInput(
                targetGraph2,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Linker.LinkableDepType.SHARED)
            .getArgs(),
        empty());
  }

  @Test
  public void staticPicLibUsedForStaticPicLinkage() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(libBuilder.build());
    CxxLibrary lib = (CxxLibrary) libBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);
    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            targetGraph,
            CxxLibraryBuilder.createDefaultPlatform(),
            Linker.LinkableDepType.STATIC_PIC);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transform(Arg.stringifyFunction())
            .toList()
            .get(0),
        Matchers.containsString("static-pic"));
  }

  @Test
  public void locationMacroExpandedLinkerFlag() throws IOException {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target = BuildTargetFactory
        .newInstance("//foo:bar")
        .withFlavors(
            CxxDescriptionEnhancer.SHARED_FLAVOR,
            CxxLibraryBuilder.createDefaultPlatform().getFlavor());
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    libBuilder.setLinkerFlags(ImmutableList.of("-Wl,--version-script=$(location //:loc)"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        libBuilder.build(),
        locBuilder.build());
    ExportFile loc = (ExportFile) locBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);
    CxxLink lib = (CxxLink) libBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);

    assertThat(lib.getDeps(), Matchers.hasItem(loc));
    assertThat(
        lib.getArgs(),
        Matchers.hasItem(
            Matchers.containsString(Preconditions.checkNotNull(loc.getPathToOutput()).toString())));
  }

  @Test
  public void locationMacroExpandedPlatformLinkerFlagPlatformMatch() throws IOException {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target = BuildTargetFactory
        .newInstance("//foo:bar")
        .withFlavors(
            CxxDescriptionEnhancer.SHARED_FLAVOR,
            CxxLibraryBuilder.createDefaultPlatform().getFlavor());
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    libBuilder.setPlatformLinkerFlags(
        PatternMatchedCollection.<ImmutableList<String>>builder()
            .add(
                Pattern.compile(CxxLibraryBuilder.createDefaultPlatform().getFlavor().toString()),
                ImmutableList.of("-Wl,--version-script=$(location //:loc)"))
            .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        libBuilder.build(),
        locBuilder.build());
    ExportFile loc = (ExportFile) locBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);
    CxxLink lib = (CxxLink) libBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);

    assertThat(lib.getDeps(), Matchers.hasItem(loc));
    assertThat(
        lib.getArgs(),
        Matchers.hasItem(
            String.format(
                "-Wl,--version-script=%s",
                Preconditions.checkNotNull(loc.getPathToOutput()).toAbsolutePath())));
    assertThat(
        lib.getArgs(),
        Matchers.not(Matchers.hasItem(loc.getPathToOutput().toAbsolutePath().toString())));
  }

  @Test
  public void locationMacroExpandedPlatformLinkerFlagNoPlatformMatch() throws IOException {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target = BuildTargetFactory
        .newInstance("//foo:bar")
        .withFlavors(
            CxxDescriptionEnhancer.SHARED_FLAVOR,
            CxxLibraryBuilder.createDefaultPlatform().getFlavor());
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    libBuilder.setPlatformLinkerFlags(
        PatternMatchedCollection.<ImmutableList<String>>builder()
            .add(
                Pattern.compile("notarealplatform"),
                ImmutableList.of("-Wl,--version-script=$(location //:loc)"))
            .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        libBuilder.build(),
        locBuilder.build());
    ExportFile loc = (ExportFile) locBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);
    CxxLink lib = (CxxLink) libBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);

    assertThat(lib.getDeps(), Matchers.not(Matchers.hasItem(loc)));
    assertThat(
        lib.getArgs(),
        Matchers.not(
            Matchers.hasItem(
                Matchers.containsString(
                    Preconditions.checkNotNull(loc.getPathToOutput()).toString()))));
  }

  @Test
  public void locationMacroExpandedExportedLinkerFlag() throws IOException {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    libBuilder.setExportedLinkerFlags(ImmutableList.of("-Wl,--version-script=$(location //:loc)"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        libBuilder.build(),
        locBuilder.build());
    ExportFile loc = (ExportFile) locBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);
    CxxLibrary lib = (CxxLibrary) libBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);

    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            targetGraph,
            CxxLibraryBuilder.createDefaultPlatform(),
            Linker.LinkableDepType.SHARED);
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(Arg.getDepsFunction(pathResolver))
            .toSet(),
        Matchers.hasItem(loc));
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transform(Arg.stringifyFunction())
            .toList(),
        Matchers.hasItem(
            Matchers.containsString(
                Preconditions.checkNotNull(loc.getPathToOutput()).toString())));
  }

  @Test
  public void locationMacroExpandedExportedPlatformLinkerFlagPlatformMatch() throws IOException {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    libBuilder.setExportedPlatformLinkerFlags(
        PatternMatchedCollection.<ImmutableList<String>>builder()
            .add(
                Pattern.compile(CxxLibraryBuilder.createDefaultPlatform().getFlavor().toString()),
                ImmutableList.of("-Wl,--version-script=$(location //:loc)"))
            .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        libBuilder.build(),
        locBuilder.build());
    ExportFile loc = (ExportFile) locBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);
    CxxLibrary lib = (CxxLibrary) libBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);

    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            targetGraph,
            CxxLibraryBuilder.createDefaultPlatform(),
            Linker.LinkableDepType.SHARED);
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(Arg.getDepsFunction(pathResolver))
            .toSet(),
        Matchers.hasItem(loc));
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transform(Arg.stringifyFunction())
            .toList(),
        Matchers.hasItem(
            Matchers.containsString(
                Preconditions.checkNotNull(loc.getPathToOutput()).toString())));
  }

  @Test
  public void locationMacroExpandedExportedPlatformLinkerFlagNoPlatformMatch() throws IOException {
    BuildTarget location = BuildTargetFactory.newInstance("//:loc");
    BuildTarget target = BuildTargetFactory
        .newInstance("//foo:bar")
        .withFlavors(
            CxxLibraryBuilder.createDefaultPlatform().getFlavor());
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExportFileBuilder locBuilder = ExportFileBuilder.newExportFileBuilder(location);
    locBuilder.setOut("somewhere.over.the.rainbow");
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    libBuilder.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))));
    libBuilder.setExportedPlatformLinkerFlags(
        PatternMatchedCollection.<ImmutableList<String>>builder()
            .add(
                Pattern.compile("notarealplatform"),
                ImmutableList.of("-Wl,--version-script=$(location //:loc)"))
            .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        libBuilder.build(),
        locBuilder.build());
    ExportFile loc = (ExportFile) locBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);
    CxxLibrary lib = (CxxLibrary) libBuilder
        .build(
            resolver,
            filesystem,
            targetGraph);

    NativeLinkableInput nativeLinkableInput =
        lib.getNativeLinkableInput(
            targetGraph,
            CxxLibraryBuilder.createDefaultPlatform(),
            Linker.LinkableDepType.SHARED);
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transformAndConcat(Arg.getDepsFunction(pathResolver))
            .toSet(),
        Matchers.not(Matchers.hasItem(loc)));
    assertThat(
        FluentIterable.from(nativeLinkableInput.getArgs())
            .transform(Arg.stringifyFunction())
            .toList(),
        Matchers.not(
            Matchers.hasItem(
                Matchers.containsString(
                    Preconditions.checkNotNull(loc.getPathToOutput()).toString()))));
  }

  @Test
  public void libraryWithoutSourcesDoesntHaveOutput() {
    BuildTarget target = BuildTargetFactory
        .newInstance("//foo:bar")
        .withFlavors(
            CxxDescriptionEnhancer.STATIC_FLAVOR,
            CxxLibraryBuilder.createDefaultPlatform().getFlavor());
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        libBuilder.build());
    BuildRule lib = libBuilder.build(
        resolver,
        filesystem,
        targetGraph);

    assertThat(lib.getPathToOutput(), nullValue());
  }

  @Test
  public void libraryWithoutSourcesDoesntBuildAnything() {
    BuildTarget target = BuildTargetFactory
        .newInstance("//foo:bar")
        .withFlavors(
            CxxDescriptionEnhancer.STATIC_FLAVOR,
            CxxLibraryBuilder.createDefaultPlatform().getFlavor());
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxLibraryBuilder libBuilder = new CxxLibraryBuilder(target);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        libBuilder.build());
    BuildRule lib = libBuilder.build(
        resolver,
        filesystem,
        targetGraph);

    assertThat(lib.getDeps(), is(empty()));
    assertThat(
        lib.getBuildSteps(FakeBuildContext.NOOP_CONTEXT, new FakeBuildableContext()),
        is(empty()));
  }

  @Test
  public void nativeLinkableDeps() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    CxxLibrary dep =
        (CxxLibrary) new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver);
    CxxLibrary rule =
        (CxxLibrary) new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
            .build(resolver);
    assertThat(
        rule.getNativeLinkableDeps(CxxLibraryBuilder.createDefaultPlatform()),
        Matchers.<NativeLinkable>contains(dep));
    assertThat(
        ImmutableList.copyOf(
            rule.getNativeLinkableExportedDeps(CxxLibraryBuilder.createDefaultPlatform())),
        Matchers.<NativeLinkable>empty());
  }

  @Test
  public void nativeLinkableExportedDeps() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    CxxLibrary dep =
        (CxxLibrary) new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver);
    CxxLibrary rule =
        (CxxLibrary) new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setExportedDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
            .build(resolver);
    assertThat(
        ImmutableList.copyOf(rule.getNativeLinkableDeps(CxxLibraryBuilder.createDefaultPlatform())),
        Matchers.<NativeLinkable>empty());
    assertThat(
        rule.getNativeLinkableExportedDeps(CxxLibraryBuilder.createDefaultPlatform()),
        Matchers.<NativeLinkable>contains(dep));
  }

}
