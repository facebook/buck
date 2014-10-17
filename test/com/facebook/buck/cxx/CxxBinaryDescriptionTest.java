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

import com.facebook.buck.cli.FakeBuckConfig;
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
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxBinaryDescriptionTest {

  private static FakeBuildRule createFakeBuildRule(
      String target,
      SourcePathResolver resolver,
      BuildRule... deps) {
    return new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target))
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build(),
        resolver);
  }

  @Test
  @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
  public void createBuildRule() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

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
    final Path archiveOutput = Paths.get("output/path/lib.a");
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    BuildRuleParams depParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(depTarget);
    CxxLibrary dep = new CxxLibrary(depParams, pathResolver) {

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
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target)
        .setDeps(ImmutableSortedSet.<BuildRule>of(dep))
        .build();

    // Create the description arg.
    CxxBinaryDescription.Arg arg = new CxxBinaryDescription.Arg();
    arg.deps = Optional.of(ImmutableSortedSet.of(dep.getBuildTarget()));
    arg.srcs = Optional.of(ImmutableList.<SourcePath>of(
        new TestSourcePath("test/bar.cpp"),
        new BuildTargetSourcePath(genSource.getBuildTarget())));
    arg.headers = Optional.of(ImmutableList.<SourcePath>of(
        new TestSourcePath("test/bar.h"),
        new BuildTargetSourcePath(genHeader.getBuildTarget())));
    arg.compilerFlags = Optional.absent();
    arg.preprocessorFlags = Optional.absent();
    arg.langPreprocessorFlags = Optional.absent();
    arg.lexSrcs = Optional.absent();
    arg.yaccSrcs = Optional.absent();
    arg.headerNamespace = Optional.absent();

    // Instantiate a description and call its `createBuildRule` method.
    DefaultCxxPlatform cxxBuckConfig = new DefaultCxxPlatform(new FakeBuckConfig());
    CxxBinaryDescription description = new CxxBinaryDescription(cxxBuckConfig);
    CxxBinary binRule = description.createBuildRule(params, resolver, arg);
    CxxLink rule = binRule.getRule();

    // Check that link rule has the expected deps: the object files for our sources and the
    // archive from the dependency.
    assertEquals(
        ImmutableSet.of(
            CxxCompilableEnhancer.createCompileBuildTarget(
                target,
                "test/bar.cpp",
                /* pic */ false),
            CxxCompilableEnhancer.createCompileBuildTarget(
                target,
                genSourceName,
                /* pic */ false),
            archive.getBuildTarget()),
        FluentIterable.from(rule.getDeps())
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

}
