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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

public class CxxLibraryTest {

  @Test
  public void cxxLibraryInterfaces() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathResolver pathResolver =
        new SourcePathResolver(
            new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    CxxPlatform cxxPlatform =
        DefaultCxxPlatforms.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));

    // Setup some dummy values for the header info.
    final BuildTarget publicHeaderTarget = BuildTargetFactory.newInstance("//:header");
    final BuildTarget publicHeaderSymlinkTreeTarget = BuildTargetFactory.newInstance("//:symlink");
    final Path publicHeaderSymlinkTreeRoot = projectFilesystem.resolve("symlink/tree/root");
    final BuildTarget privateHeaderTarget = BuildTargetFactory.newInstance("//:privateheader");
    final BuildTarget privateHeaderSymlinkTreeTarget = BuildTargetFactory.newInstance(
        "//:privatesymlink");
    final Path privateHeaderSymlinkTreeRoot =
        projectFilesystem.resolve("private/symlink/tree/root");

    // Setup some dummy values for the library archive info.
    final BuildRule archive = new FakeBuildRule("//:archive", pathResolver);

    // Setup some dummy values for the library archive info.
    final BuildRule sharedLibrary = new FakeBuildRule("//:shared", pathResolver);
    final Path sharedLibraryOutput = Paths.get("output/path/lib.so");
    final String sharedLibrarySoname = "lib.so";

    // Construct a CxxLibrary object to test.
    FakeCxxLibrary cxxLibrary = new FakeCxxLibrary(
        params,
        pathResolver,
        publicHeaderTarget,
        publicHeaderSymlinkTreeTarget,
        publicHeaderSymlinkTreeRoot,
        privateHeaderTarget,
        privateHeaderSymlinkTreeTarget,
        privateHeaderSymlinkTreeRoot,
        archive,
        sharedLibrary,
        sharedLibraryOutput,
        sharedLibrarySoname,
        ImmutableSortedSet.<BuildTarget>of());

    // Verify that we get the header/symlink targets and root via the CxxPreprocessorDep
    // interface.
    CxxPreprocessorInput expectedPublicCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addRules(publicHeaderTarget, publicHeaderSymlinkTreeTarget)
        .addIncludeRoots(publicHeaderSymlinkTreeRoot)
        .build();
    assertEquals(
        expectedPublicCxxPreprocessorInput,
        cxxLibrary.getCxxPreprocessorInput(
            cxxPlatform,
            HeaderVisibility.PUBLIC));

    CxxPreprocessorInput expectedPrivateCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addRules(privateHeaderTarget, privateHeaderSymlinkTreeTarget)
        .addIncludeRoots(privateHeaderSymlinkTreeRoot)
        .build();
    assertEquals(
        expectedPrivateCxxPreprocessorInput,
        cxxLibrary.getCxxPreprocessorInput(
            cxxPlatform,
            HeaderVisibility.PRIVATE));

    // Verify that we get the static archive and its build target via the NativeLinkable
    // interface.
    NativeLinkableInput expectedStaticNativeLinkableInput = NativeLinkableInput.of(
        ImmutableList.<Arg>of(
            new SourcePathArg(
                pathResolver,
                new BuildTargetSourcePath(archive.getBuildTarget()))),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
    assertEquals(
        expectedStaticNativeLinkableInput,
        cxxLibrary.getNativeLinkableInput(
            cxxPlatform,
            Linker.LinkableDepType.STATIC));

    // Verify that we get the static archive and its build target via the NativeLinkable
    // interface.
    NativeLinkableInput expectedSharedNativeLinkableInput = NativeLinkableInput.of(
        ImmutableList.<Arg>of(
            new SourcePathArg(
                pathResolver,
                new BuildTargetSourcePath(sharedLibrary.getBuildTarget()))),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
    assertEquals(
        expectedSharedNativeLinkableInput,
        cxxLibrary.getNativeLinkableInput(
            cxxPlatform,
            Linker.LinkableDepType.SHARED));

    // Verify that the implemented BuildRule methods are effectively unused.
    assertEquals(ImmutableList.<Step>of(), cxxLibrary.getBuildSteps(null, null));
    assertNull(cxxLibrary.getPathToOutput());
  }

  @Test
  public void staticLinkage() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    CxxPlatform cxxPlatform =
        DefaultCxxPlatforms.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));

    BuildTarget staticPicLibraryTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(
                cxxPlatform.getFlavor(),
                CxxDescriptionEnhancer.STATIC_PIC_FLAVOR)
            .build();
    ruleResolver.addToIndex(
        new FakeBuildRule(
            new FakeBuildRuleParamsBuilder(staticPicLibraryTarget).build(),
            pathResolver));

    // Construct a CxxLibrary object to test.
    CxxLibrary cxxLibrary = new CxxLibrary(
        params,
        ruleResolver,
        pathResolver,
        FluentIterable.from(params.getDeclaredDeps().get())
            .filter(NativeLinkable.class),
        /* headerOnly */ Predicates.<CxxPlatform>alwaysFalse(),
        Functions.constant(ImmutableMultimap.<CxxSource.Type, String>of()),
        /* exportedLinkerFlags */ Functions.constant(ImmutableList.<Arg>of()),
        /* linkTargetInput */ Functions.constant(NativeLinkableInput.of()),
        /* supportedPlatformsRegex */ Optional.<Pattern>absent(),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of(),
        NativeLinkable.Linkage.STATIC,
        /* linkWhole */ false,
        Optional.<String>absent(),
        ImmutableSortedSet.<BuildTarget>of(),
        /* isAsset */ false);

    assertThat(
        cxxLibrary.getSharedLibraries(cxxPlatform).entrySet(),
        Matchers.empty());

    // Verify that
    NativeLinkableInput expectedSharedNativeLinkableInput =
        NativeLinkableInput.of(
            ImmutableList.<Arg>of(
                new SourcePathArg(
                    pathResolver,
                    new BuildTargetSourcePath(staticPicLibraryTarget))),
            ImmutableSet.<FrameworkPath>of(),
            ImmutableSet.<FrameworkPath>of());
    assertEquals(
        expectedSharedNativeLinkableInput,
        cxxLibrary.getNativeLinkableInput(
            cxxPlatform,
            Linker.LinkableDepType.SHARED));
  }

}
