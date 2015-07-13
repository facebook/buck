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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class CxxLibraryDescriptionTest {

  private static <T> void assertContains(List<T> container, Iterable<T> items) {
    for (T item : items) {
      assertThat(container, Matchers.hasItem(item));
    }
  }

  private static <T> void assertNotContains(List<T> container, Iterable<T> items) {
    for (T item : items) {
      assertThat(container, Matchers.not(Matchers.hasItem(item)));
    }
  }

  @Test
  @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
  public void createBuildRule() {
    BuildRuleResolver resolver = new BuildRuleResolver();
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
    final Path archiveOutput = Paths.get("output/path/lib.a");
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    BuildRuleParams depParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(depTarget);
    AbstractCxxLibrary dep = new AbstractCxxLibrary(depParams, pathResolver) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput(
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
          CxxPlatform cxxPlatform,
          HeaderVisibility headerVisibility) {
        return ImmutableMap.of(
            getBuildTarget(),
            getCxxPreprocessorInput(cxxPlatform, headerVisibility));
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType type) {
        return NativeLinkableInput.of(
            ImmutableList.<SourcePath>of(
                new BuildTargetSourcePath(archive.getBuildTarget())),
            ImmutableList.of(archiveOutput.toString()),
            ImmutableSet.<Path>of());
      }

      @Override
      public Optional<Linker.LinkableDepType> getPreferredLinkage(CxxPlatform cxxPlatform) {
        return Optional.absent();
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents(CxxPlatform cxxPlatform) {
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
      public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
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

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(target, cxxPlatform);
    String headerName = "test/bar.h";
    String privateHeaderName = "test/bar_private.h";
    CxxLibraryBuilder cxxLibraryBuilder = (CxxLibraryBuilder) new CxxLibraryBuilder(target)
        .setExportedHeaders(
            ImmutableSortedSet.<SourcePath>of(
                new TestSourcePath(headerName),
                new BuildTargetSourcePath(genHeaderTarget)))
        .setHeaders(
            ImmutableSortedSet.<SourcePath>of(new TestSourcePath(privateHeaderName)))
        .setSrcs(
            ImmutableList.of(
                SourceWithFlags.of(new TestSourcePath("test/bar.cpp")),
                SourceWithFlags.of(new BuildTargetSourcePath(genSourceTarget))))
        .setFrameworkSearchPaths(
            ImmutableSet.of(
                Paths.get("/some/framework/path"),
                Paths.get("/another/framework/path")))
        .setDeps(ImmutableSortedSet.of(dep.getBuildTarget()));

    CxxLibrary rule = (CxxLibrary) cxxLibraryBuilder.build(
        resolver,
        new FakeProjectFilesystem(),
        TargetGraphFactory.newInstance(
            cxxLibraryBuilder.build(),
            genSourceBuilder.build(),
            genHeaderBuilder.build(),
            GenruleBuilder.newGenruleBuilder(depTarget)
                .build()));

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
                        new TestSourcePath(headerName))
                    .putNameToPathMap(
                        Paths.get(genHeaderName),
                        new BuildTargetSourcePath(genHeaderTarget))
                    .putFullNameToPathMap(
                        headerRoot.resolve(headerName),
                        new TestSourcePath(headerName))
                    .putFullNameToPathMap(
                        headerRoot.resolve(genHeaderName),
                        new BuildTargetSourcePath(genHeaderTarget))
                    .build())
            .addIncludeRoots(
                CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
                    target,
                    cxxPlatform.getFlavor(),
                    HeaderVisibility.PUBLIC))
            .addFrameworkRoots(
                Paths.get("/some/framework/path"),
                Paths.get("/another/framework/path"))
            .build(),
        rule.getCxxPreprocessorInput(
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
                        new TestSourcePath(privateHeaderName))
                    .putFullNameToPathMap(
                        privateHeaderRoot.resolve(privateHeaderName),
                        new TestSourcePath(privateHeaderName))
                    .build())
            .addIncludeRoots(
                CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
                    target,
                    cxxPlatform.getFlavor(),
                    HeaderVisibility.PRIVATE))
            .addFrameworkRoots(
                Paths.get("/some/framework/path"),
                Paths.get("/another/framework/path"))
            .build(),
        rule.getCxxPreprocessorInput(
            cxxPlatform,
            HeaderVisibility.PRIVATE));

    // Verify that the archive rule has the correct deps: the object files from our sources.
    rule.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC);
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
    BuildRuleResolver resolver = new BuildRuleResolver();
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform cxxPlatform = CxxLibraryBuilder.createDefaultPlatform();

    String soname = "test_soname";

    // Generate the C++ library rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    AbstractCxxSourceBuilder<CxxLibraryDescription.Arg> ruleBuilder = new CxxLibraryBuilder(target)
        .setSoname(soname)
        .setSrcs(ImmutableList.of(SourceWithFlags.of(new TestSourcePath("foo.cpp"))));
    CxxLibrary rule = (CxxLibrary) ruleBuilder.build(
        resolver,
        filesystem,
        TargetGraphFactory.newInstance(ruleBuilder.build()));

    Linker linker = cxxPlatform.getLd();
    NativeLinkableInput input = rule.getNativeLinkableInput(
        cxxPlatform,
        Linker.LinkableDepType.SHARED);

    ImmutableList<SourcePath> inputs = ImmutableList.copyOf(input.getInputs());
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
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxPlatform cxxPlatform = CxxLibraryBuilder.createDefaultPlatform();

    // Setup the target name and build params.
    BuildTarget target = BuildTargetFactory.newInstance("//:test");

    // Lookup the link whole flags.
    Path staticLib =
        CxxDescriptionEnhancer.getStaticLibraryPath(
            target,
            cxxPlatform.getFlavor(),
            CxxSourceRuleFactory.PicType.PDC);
    Linker linker = cxxPlatform.getLd();
    Set<String> linkWholeFlags = Sets.newHashSet(linker.linkWhole(staticLib.toString()));
    linkWholeFlags.remove(staticLib.toString());

    // First, create a cxx library without using link whole.
    CxxLibraryBuilder normalBuilder = new CxxLibraryBuilder(target);
    CxxLibrary normal = (CxxLibrary) normalBuilder.build(
        new BuildRuleResolver(),
        filesystem,
        TargetGraphFactory.newInstance(normalBuilder.build()));

    // Verify that the linker args contains the link whole flags.
    assertNotContains(
        normal.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC).getArgs(),
        linkWholeFlags);

    // Create a cxx library using link whole.
    AbstractCxxSourceBuilder<CxxLibraryDescription.Arg> linkWholeBuilder =
        new CxxLibraryBuilder(target)
            .setLinkWhole(true)
            .setSrcs(ImmutableList.of(SourceWithFlags.of(new TestSourcePath("foo.cpp"))));

    CxxLibrary linkWhole = (CxxLibrary) linkWholeBuilder.build(
        new BuildRuleResolver(),
        filesystem,
        TargetGraphFactory.newInstance(linkWholeBuilder.build()));

    // Verify that the linker args contains the link whole flags.
    assertContains(
        linkWhole.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC).getArgs(),
        linkWholeFlags);
  }

  @Test
  @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
  public void createCxxLibraryBuildRules() {
    BuildRuleResolver resolver = new BuildRuleResolver();
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
    final Path staticLibraryOutput = Paths.get("output/path/lib.a");
    final BuildRule sharedLibraryDep = new FakeBuildRule("//:shared", pathResolver);
    final Path sharedLibraryOutput = Paths.get("output/path/lib.so");
    final String sharedLibrarySoname = "soname";
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    BuildRuleParams depParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(depTarget);
    AbstractCxxLibrary dep = new AbstractCxxLibrary(depParams, pathResolver) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput(
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
          CxxPlatform cxxPlatform,
          HeaderVisibility headerVisibility) {
        return ImmutableMap.of(
            getBuildTarget(),
            getCxxPreprocessorInput(cxxPlatform, headerVisibility));
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType type) {
        return type == Linker.LinkableDepType.STATIC ?
            NativeLinkableInput.of(
                ImmutableList.<SourcePath>of(
                    new BuildTargetSourcePath(
                        staticLibraryDep.getBuildTarget())),
                ImmutableList.of(staticLibraryOutput.toString()),
                ImmutableSet.<Path>of()) :
            NativeLinkableInput.of(
                ImmutableList.<SourcePath>of(
                    new BuildTargetSourcePath(
                        sharedLibraryDep.getBuildTarget())),
                ImmutableList.of(sharedLibraryOutput.toString()),
                ImmutableSet.<Path>of());
      }

      @Override
      public Optional<Linker.LinkableDepType> getPreferredLinkage(CxxPlatform cxxPlatform) {
        return Optional.absent();
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents(CxxPlatform cxxPlatform) {
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
      public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
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
    CxxSourceRuleFactory cxxSourceRuleFactory = CxxSourceRuleFactoryHelper.of(target, cxxPlatform);
    CxxLibraryBuilder cxxLibraryBuilder = (CxxLibraryBuilder) new CxxLibraryBuilder(target)
        .setExportedHeaders(
            ImmutableMap.<String, SourcePath>of(
                genHeaderName, new BuildTargetSourcePath(genHeaderTarget)))
        .setSrcs(
            ImmutableMap.of(
                sourceName,
                SourceWithFlags.of(new TestSourcePath(sourceName)),
                genSourceName,
                SourceWithFlags.of(new BuildTargetSourcePath(genSourceTarget))))
        .setFrameworkSearchPaths(
            ImmutableSet.of(
                Paths.get("/some/framework/path"),
                Paths.get("/another/framework/path")))
        .setDeps(ImmutableSortedSet.of(dep.getBuildTarget()));

    // Construct C/C++ library build rules.
    CxxLibrary rule = (CxxLibrary) cxxLibraryBuilder.build(
        resolver,
        new FakeProjectFilesystem(),
        TargetGraphFactory.newInstance(
            cxxLibraryBuilder.build(),
            genSourceBuilder.build(),
            genHeaderBuilder.build(),
            GenruleBuilder.newGenruleBuilder(depTarget)
                .build()));

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
                CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
                    target,
                    cxxPlatform.getFlavor(),
                    HeaderVisibility.PUBLIC))
            .addFrameworkRoots(
                Paths.get("/some/framework/path"),
                Paths.get("/another/framework/path"))
            .build(),
        rule.getCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PUBLIC));

    // Verify that the archive rule has the correct deps: the object files from our sources.
    rule.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC);
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
    rule.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.SHARED);
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
        rule.getPythonPackageComponents(cxxPlatform));
  }

  @Test
  public void supportedPlatforms() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");

    // First, make sure without any platform regex, we get something back for each of the interface
    // methods.
    CxxLibraryBuilder cxxLibraryBuilder =
        (CxxLibraryBuilder) new CxxLibraryBuilder(target)
            .setSrcs(ImmutableList.of(SourceWithFlags.of(new TestSourcePath("test.c"))));
    CxxLibrary cxxLibrary =
        (CxxLibrary) cxxLibraryBuilder
            .build(
                new BuildRuleResolver(),
                filesystem,
                TargetGraphFactory.newInstance(cxxLibraryBuilder.build()));
    assertThat(
        cxxLibrary.getSharedLibraries(CxxPlatformUtils.DEFAULT_PLATFORM).entrySet(),
        Matchers.not(Matchers.empty()));
    assertThat(
        cxxLibrary.getPythonPackageComponents(
            CxxPlatformUtils.DEFAULT_PLATFORM).getNativeLibraries().entrySet(),
        Matchers.not(Matchers.empty()));
    assertThat(
        cxxLibrary.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.SHARED).getArgs(),
        Matchers.not(Matchers.empty()));

    // Now, verify we get nothing when the supported platform regex excludes our platform.
    cxxLibraryBuilder.setSupportedPlatformsRegex(Pattern.compile("nothing"));
    cxxLibrary =
        (CxxLibrary) cxxLibraryBuilder
            .build(
                new BuildRuleResolver(),
                filesystem,
                TargetGraphFactory.newInstance(cxxLibraryBuilder.build()));
    assertThat(
        cxxLibrary.getSharedLibraries(CxxPlatformUtils.DEFAULT_PLATFORM).entrySet(),
        Matchers.empty());
    assertThat(
        cxxLibrary.getPythonPackageComponents(
            CxxPlatformUtils.DEFAULT_PLATFORM).getNativeLibraries().entrySet(),
        Matchers.empty());
    assertThat(
        cxxLibrary.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.SHARED).getArgs(),
        Matchers.empty());
  }

}
