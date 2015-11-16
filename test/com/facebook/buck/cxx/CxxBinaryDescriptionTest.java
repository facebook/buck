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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.python.PythonPlatform;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
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
import java.util.Set;
import java.util.regex.Pattern;

public class CxxBinaryDescriptionTest {

  private static FakeBuildRule createFakeBuildRule(
      String target,
      SourcePathResolver resolver,
      BuildRule... deps) {
    return new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target))
            .setDeclaredDeps(ImmutableSortedSet.copyOf(deps))
            .build(),
        resolver);
  }

  @Test
  @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
  public void createBuildRule() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    CxxPlatform cxxPlatform = CxxBinaryBuilder.createDefaultPlatform();

    // Setup a genrule the generates a header we'll list.
    String genHeaderName = "test/foo.h";
    BuildTarget genHeaderTarget = BuildTargetFactory.newInstance("//:genHeader");
    Genrule genHeader = (Genrule) GenruleBuilder
        .newGenruleBuilder(genHeaderTarget)
        .setOut(genHeaderName)
        .build(resolver);

    // Setup a genrule the generates a source we'll list.
    String genSourceName = "test/foo.cpp";
    BuildTarget genSourceTarget = BuildTargetFactory.newInstance("//:genSource");
    Genrule genSource = (Genrule) GenruleBuilder
        .newGenruleBuilder(genSourceTarget)
        .setOut(genSourceName)
        .build(resolver);

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    final BuildRule header = createFakeBuildRule("//:header", pathResolver);
    final BuildRule headerSymlinkTree = createFakeBuildRule("//:symlink", pathResolver);
    final Path headerSymlinkTreeRoot = Paths.get("symlink/tree/root");
    final BuildRule archive = createFakeBuildRule("//:archive", pathResolver);
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
    resolver.addAllToIndex(ImmutableList.of(header, headerSymlinkTree, archive, dep));

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CxxBinaryBuilder cxxBinaryBuilder =
        (CxxBinaryBuilder) new CxxBinaryBuilder(target)
              .setSrcs(
                  ImmutableSortedSet.of(
                      SourceWithFlags.of(new FakeSourcePath("test/bar.cpp")),
                      SourceWithFlags.of(
                          new BuildTargetSourcePath(
                              genSource.getBuildTarget()))))
              .setHeaders(
                  ImmutableSortedSet.<SourcePath>of(
                      new FakeSourcePath("test/bar.h"),
                      new BuildTargetSourcePath(genHeader.getBuildTarget())))
              .setDeps(ImmutableSortedSet.of(dep.getBuildTarget()));
    CxxBinary binRule = (CxxBinary) cxxBinaryBuilder.build(resolver);
    CxxLink rule = binRule.getRule();
    CxxSourceRuleFactory cxxSourceRuleFactory =
        new CxxSourceRuleFactory(
            cxxBinaryBuilder.createBuildRuleParams(resolver, projectFilesystem),
            resolver,
            pathResolver,
            cxxPlatform,
            ImmutableList.<CxxPreprocessorInput>of(),
            ImmutableList.<String>of(),
            Optional.<SourcePath>absent());

    // Check that link rule has the expected deps: the object files for our sources and the
    // archive from the dependency.
    assertEquals(
        ImmutableSet.of(
            cxxSourceRuleFactory.createCompileBuildTarget(
                "test/bar.cpp",
                CxxSourceRuleFactory.PicType.PDC),
            cxxSourceRuleFactory.createCompileBuildTarget(
                genSourceName,
                CxxSourceRuleFactory.PicType.PDC),
            archive.getBuildTarget()),
        FluentIterable.from(rule.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the preproces rule for our user-provided source has correct deps setup
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
                HeaderVisibility.PRIVATE)),
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

    // Verify that the preproces rule for our user-provided source has correct deps setup
    // for the various header rules.
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
                HeaderVisibility.PRIVATE)),
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
  public void staticPicLinkStyle() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    new CxxBinaryBuilder(target)
        .setSrcs(
            ImmutableSortedSet.of(
                SourceWithFlags.of(new PathSourcePath(filesystem, Paths.get("test.cpp")))))
        .build(resolver, filesystem);
  }

  @Test
  public void runtimeDepOnDeps() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new BuildRuleResolver();
    Set<TargetNode<?>> targetNodes = Sets.newTreeSet();
    BuildRule leafCxxBinary =
        new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .build(resolver, filesystem, targetNodes);
    BuildRule cxxLibrary =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setDeps(ImmutableSortedSet.of(leafCxxBinary.getBuildTarget()))
            .build(resolver, filesystem, targetNodes);
    CxxBinary topLevelCxxBinary =
        (CxxBinary) new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:bin"))
            .setDeps(ImmutableSortedSet.of(cxxLibrary.getBuildTarget()))
            .build(resolver, filesystem, targetNodes);
    assertThat(
        BuildRules.getTransitiveRuntimeDeps(topLevelCxxBinary),
        Matchers.hasItem(leafCxxBinary));
  }

  @Test
  public void linkerFlagsLocationMacro() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    Genrule dep =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    CxxBinaryBuilder builder =
        (CxxBinaryBuilder) new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setLinkerFlags(ImmutableList.of("--linker-script=$(location //:dep)"));
    assertThat(
        builder.findImplicitDeps(),
        Matchers.hasItem(dep.getBuildTarget()));
    CxxLink binary = ((CxxBinary) builder.build(resolver)).getRule();
    assertThat(
        binary.getArgs(),
        Matchers.hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath())));
    assertThat(
        binary.getDeps(),
        Matchers.hasItem(dep));
  }

  @Test
  public void platformLinkerFlagsLocationMacroWithMatch() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    Genrule dep =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    CxxBinaryBuilder builder =
        (CxxBinaryBuilder) new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setPlatformLinkerFlags(
                new PatternMatchedCollection.Builder<ImmutableList<String>>()
                    .add(
                        Pattern.compile(
                            Pattern.quote(
                                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor().toString())),
                        ImmutableList.of("--linker-script=$(location //:dep)"))
                    .build());
    assertThat(
        builder.findImplicitDeps(),
        Matchers.hasItem(dep.getBuildTarget()));
    CxxLink binary = ((CxxBinary) builder.build(resolver)).getRule();
    assertThat(
        binary.getArgs(),
        Matchers.hasItem(String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath())));
    assertThat(
        binary.getDeps(),
        Matchers.hasItem(dep));
  }

  @Test
  public void platformLinkerFlagsLocationMacroWithoutMatch() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    Genrule dep =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    CxxBinaryBuilder builder =
        (CxxBinaryBuilder) new CxxBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setPlatformLinkerFlags(
                new PatternMatchedCollection.Builder<ImmutableList<String>>()
                    .add(
                        Pattern.compile("nothing matches this string"),
                        ImmutableList.of("--linker-script=$(location //:dep)"))
                    .build());
    assertThat(
        builder.findImplicitDeps(),
        Matchers.hasItem(dep.getBuildTarget()));
    CxxLink binary = ((CxxBinary) builder.build(resolver)).getRule();
    assertThat(
        binary.getArgs(),
        Matchers.not(
            Matchers.hasItem(
                String.format("--linker-script=%s", dep.getAbsoluteOutputFilePath()))));
    assertThat(
        binary.getDeps(),
        Matchers.not(Matchers.hasItem(dep)));
  }

}
