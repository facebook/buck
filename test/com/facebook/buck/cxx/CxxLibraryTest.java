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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.Step;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class CxxLibraryTest {

  @Test
  public void cxxLibraryInterfaces() {
    SourcePathResolver pathResolver =
        new SourcePathResolver(
            new SourcePathRuleFinder(
                new BuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));

    // Setup some dummy values for the header info.
    final BuildTarget publicHeaderTarget = BuildTargetFactory.newInstance("//:header");
    final BuildTarget publicHeaderSymlinkTreeTarget = BuildTargetFactory.newInstance("//:symlink");
    final BuildTarget privateHeaderTarget = BuildTargetFactory.newInstance("//:privateheader");
    final BuildTarget privateHeaderSymlinkTreeTarget =
        BuildTargetFactory.newInstance("//:privatesymlink");

    // Setup some dummy values for the library archive info.
    final BuildRule archive =
        new FakeBuildRule("//:archive", pathResolver).setOutputFile("libarchive.a");

    // Setup some dummy values for the library archive info.
    final BuildRule sharedLibrary =
        new FakeBuildRule("//:shared", pathResolver).setOutputFile("libshared.so");
    final Path sharedLibraryOutput = Paths.get("output/path/lib.so");
    final String sharedLibrarySoname = "lib.so";

    // Construct a CxxLibrary object to test.
    FakeCxxLibrary cxxLibrary =
        new FakeCxxLibrary(
            params,
            publicHeaderTarget,
            publicHeaderSymlinkTreeTarget,
            privateHeaderTarget,
            privateHeaderSymlinkTreeTarget,
            archive,
            sharedLibrary,
            sharedLibraryOutput,
            sharedLibrarySoname,
            ImmutableSortedSet.of());

    // Verify that we get the header/symlink targets and root via the CxxPreprocessorDep
    // interface.
    CxxPreprocessorInput expectedPublicCxxPreprocessorInput =
        CxxPreprocessorInput.builder()
            .addIncludes(
                CxxSymlinkTreeHeaders.builder()
                    .setIncludeType(CxxPreprocessables.IncludeType.LOCAL)
                    .putNameToPathMap(
                        Paths.get("header.h"), new DefaultBuildTargetSourcePath(publicHeaderTarget))
                    .setRoot(new DefaultBuildTargetSourcePath(publicHeaderSymlinkTreeTarget))
                    .build())
            .build();
    assertEquals(
        expectedPublicCxxPreprocessorInput,
        cxxLibrary.getCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PUBLIC));

    CxxPreprocessorInput expectedPrivateCxxPreprocessorInput =
        CxxPreprocessorInput.builder()
            .addIncludes(
                CxxSymlinkTreeHeaders.builder()
                    .setIncludeType(CxxPreprocessables.IncludeType.LOCAL)
                    .setRoot(new DefaultBuildTargetSourcePath(privateHeaderSymlinkTreeTarget))
                    .putNameToPathMap(
                        Paths.get("header.h"),
                        new DefaultBuildTargetSourcePath(privateHeaderTarget))
                    .build())
            .build();
    assertEquals(
        expectedPrivateCxxPreprocessorInput,
        cxxLibrary.getCxxPreprocessorInput(cxxPlatform, HeaderVisibility.PRIVATE));

    // Verify that we get the static archive and its build target via the NativeLinkable
    // interface.
    NativeLinkableInput expectedStaticNativeLinkableInput =
        NativeLinkableInput.of(
            ImmutableList.of(SourcePathArg.of(archive.getSourcePathToOutput())),
            ImmutableSet.of(),
            ImmutableSet.of());
    assertEquals(
        expectedStaticNativeLinkableInput,
        cxxLibrary.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.STATIC));

    // Verify that we get the static archive and its build target via the NativeLinkable
    // interface.
    NativeLinkableInput expectedSharedNativeLinkableInput =
        NativeLinkableInput.of(
            ImmutableList.of(SourcePathArg.of(sharedLibrary.getSourcePathToOutput())),
            ImmutableSet.of(),
            ImmutableSet.of());
    assertEquals(
        expectedSharedNativeLinkableInput,
        cxxLibrary.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.SHARED));

    // Verify that the implemented BuildRule methods are effectively unused.
    assertEquals(ImmutableList.<Step>of(), cxxLibrary.getBuildSteps(null, null));
    assertNull(cxxLibrary.getSourcePathToOutput());
  }

  @Test
  public void headerOnlyExports() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));

    BuildTarget staticPicLibraryTarget =
        params
            .getBuildTarget()
            .withAppendedFlavors(cxxPlatform.getFlavor(), CxxDescriptionEnhancer.STATIC_PIC_FLAVOR);
    ruleResolver.addToIndex(
        new FakeBuildRule(
            new FakeBuildRuleParamsBuilder(staticPicLibraryTarget).build(), pathResolver));

    FrameworkPath frameworkPath =
        FrameworkPath.ofSourcePath(
            new DefaultBuildTargetSourcePath(BuildTargetFactory.newInstance("//foo:baz")));

    // Construct a CxxLibrary object to test.
    CxxLibrary cxxLibrary =
        new CxxLibrary(
            params,
            ruleResolver,
            CxxDeps.EMPTY,
            CxxDeps.EMPTY,
            /* headerOnly */ x -> true,
            Functions.constant(StringArg.from("-ldl")),
            /* linkTargetInput */ Functions.constant(NativeLinkableInput.of()),
            /* supportedPlatformsRegex */ Optional.empty(),
            ImmutableSet.of(frameworkPath),
            ImmutableSet.of(),
            NativeLinkable.Linkage.STATIC,
            /* linkWhole */ false,
            Optional.empty(),
            ImmutableSortedSet.of(),
            /* isAsset */ false,
            true,
            true);

    NativeLinkableInput expectedSharedNativeLinkableInput =
        NativeLinkableInput.of(
            StringArg.from("-ldl"), ImmutableSet.of(frameworkPath), ImmutableSet.of());

    assertEquals(
        expectedSharedNativeLinkableInput,
        cxxLibrary.getNativeLinkableInput(cxxPlatform, Linker.LinkableDepType.SHARED));
  }
}
