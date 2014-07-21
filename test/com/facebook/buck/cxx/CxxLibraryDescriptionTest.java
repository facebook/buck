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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxLibraryDescriptionTest {

  private static FakeBuildRule createFakeBuildRule(
      String target,
      BuildRule... deps) {
    return new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target))
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build());
  }

  @Test
  public void createBuildRule() {
    BuildRuleResolver resolver = new BuildRuleResolver();

    // Setup a genrule the generates a header we'll list.
    String genHeaderName = "test/foo.h";
    BuildTarget genHeaderTarget = BuildTargetFactory.newInstance("//:genHeader");
    Genrule genHeader = GenruleBuilder.createGenrule(genHeaderTarget)
        .setOut(genHeaderName)
        .build();

    // Setup a genrule the generates a source we'll list.
    String genSourceName = "test/foo.cpp";
    BuildTarget genSourceTarget = BuildTargetFactory.newInstance("//:genSource");
    Genrule genSource = GenruleBuilder.createGenrule(genSourceTarget)
        .setOut(genSourceName)
        .build();

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    final BuildRule header = createFakeBuildRule("//:header");
    final BuildRule headerSymlinkTree = createFakeBuildRule("//:symlink");
    final Path headerSymlinkTreeRoot = Paths.get("symlink/tree/root");
    final BuildRule archive = createFakeBuildRule("//:archive");
    final Path archiveOutput = Paths.get("output/path/lib.a");
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    BuildRuleParams depParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(depTarget);
    CxxLibrary dep = new CxxLibrary(depParams) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput() {
        return new CxxPreprocessorInput(
            ImmutableSet.of(
                header.getBuildTarget(),
                headerSymlinkTree.getBuildTarget()),
            ImmutableList.<String>of(),
            ImmutableList.<String>of(),
            ImmutableList.of(headerSymlinkTreeRoot),
            ImmutableList.<Path>of());
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput() {
        return new NativeLinkableInput(
            ImmutableSet.<BuildTarget>of(archive.getBuildTarget()),
            ImmutableList.<Path>of(archiveOutput),
            ImmutableList.<String>of(archiveOutput.toString()));
      }

    };
    resolver.addAllToIndex(ImmutableList.of(header, headerSymlinkTree, archive));

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target)
        .setDeps(ImmutableSortedSet.<BuildRule>of(dep))
        .build();

    // Create the description arg.
    CxxLibraryDescription.Arg arg = new CxxLibraryDescription.Arg();
    arg.deps = Optional.of(ImmutableSortedSet.<BuildRule>of(dep));
    arg.srcs = Optional.of(ImmutableList.<SourcePath>of(
        new TestSourcePath("test/bar.cpp"),
        new BuildRuleSourcePath(genSource)));
    arg.headers = Optional.of(ImmutableList.<SourcePath>of(
        new TestSourcePath("test/bar.h"),
        new BuildRuleSourcePath(genHeader)));
    arg.compilerFlags = Optional.absent();
    arg.propagatedPpFlags = Optional.absent();
    arg.preprocessorFlags = Optional.absent();

    // Instantiate a description and call its `createBuildRule` method.
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(new FakeBuckConfig());
    CxxLibraryDescription description = new CxxLibraryDescription(cxxBuckConfig);
    CxxLibrary rule = description.createBuildRule(params, resolver, arg);

    assertEquals(
        new CxxPreprocessorInput(
            ImmutableSet.of(
                CxxDescriptionEnhancer.createHeaderTarget(target),
                CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target)),
            ImmutableList.<String>of(),
            ImmutableList.<String>of(),
            ImmutableList.of(
                CxxDescriptionEnhancer.getHeaderSymlinkTreePath(target)),
            ImmutableList.<Path>of()),
        rule.getCxxPreprocessorInput());

    // Verify that the archive rule has the correct deps: the object files from our sources.
    BuildRule archiveRule = resolver.get(
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(target));
    assertNotNull(archiveRule);
    assertEquals(
        ImmutableSet.of(
            CxxCompilableEnhancer.createCompileBuildTarget(target, "test/bar.cpp"),
            CxxCompilableEnhancer.createCompileBuildTarget(target, genSourceName)),
        FluentIterable.from(archiveRule.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule compileRule1 = resolver.get(
        CxxCompilableEnhancer.createCompileBuildTarget(target, "test/bar.cpp"));
    assertNotNull(compileRule1);
    assertEquals(
        ImmutableSet.of(
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderTarget(target),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target)),
        FluentIterable.from(compileRule1.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule compileRule2 = resolver.get(
        CxxCompilableEnhancer.createCompileBuildTarget(target, genSourceName));
    assertNotNull(compileRule2);
    assertEquals(
        ImmutableSet.of(
            genSourceTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderTarget(target),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target)),
        FluentIterable.from(compileRule2.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());
  }

}
