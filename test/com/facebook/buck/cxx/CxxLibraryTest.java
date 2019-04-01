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

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class CxxLibraryTest {

  @Test
  public void cxxLibraryInterfaces() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = TestBuildRuleParams.create();
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));

    // Setup some dummy values for the header info.
    BuildTarget publicHeaderTarget = BuildTargetFactory.newInstance("//:header");
    BuildTarget publicHeaderSymlinkTreeTarget = BuildTargetFactory.newInstance("//:symlink");
    BuildTarget privateHeaderTarget = BuildTargetFactory.newInstance("//:privateheader");
    BuildTarget privateHeaderSymlinkTreeTarget =
        BuildTargetFactory.newInstance("//:privatesymlink");

    // Setup some dummy values for the library archive info.
    BuildRule archive = new FakeBuildRule("//:archive").setOutputFile("libarchive.a");

    // Setup some dummy values for the library archive info.
    BuildRule sharedLibrary = new FakeBuildRule("//:shared").setOutputFile("libshared.so");
    Path sharedLibraryOutput = Paths.get("output/path/lib.so");
    String sharedLibrarySoname = "lib.so";

    // Construct a CxxLibrary object to test.
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    FakeCxxLibrary cxxLibrary =
        new FakeCxxLibrary(
            target,
            new FakeProjectFilesystem(),
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
                    .setNameToPathMap(
                        ImmutableSortedMap.of(
                            Paths.get("header.h"),
                            DefaultBuildTargetSourcePath.of(publicHeaderTarget)))
                    .setRoot(DefaultBuildTargetSourcePath.of(publicHeaderSymlinkTreeTarget))
                    .setIncludeRoot(
                        Either.ofRight(
                            DefaultBuildTargetSourcePath.of(publicHeaderSymlinkTreeTarget)))
                    .setSymlinkTreeClass(HeaderSymlinkTree.class.getName())
                    .build())
            .build();
    assertEquals(
        expectedPublicCxxPreprocessorInput,
        cxxLibrary.getCxxPreprocessorInput(cxxPlatform, graphBuilder));

    CxxPreprocessorInput expectedPrivateCxxPreprocessorInput =
        CxxPreprocessorInput.builder()
            .addIncludes(
                CxxSymlinkTreeHeaders.builder()
                    .setIncludeType(CxxPreprocessables.IncludeType.LOCAL)
                    .setRoot(DefaultBuildTargetSourcePath.of(privateHeaderSymlinkTreeTarget))
                    .setIncludeRoot(
                        Either.ofRight(
                            DefaultBuildTargetSourcePath.of(privateHeaderSymlinkTreeTarget)))
                    .setNameToPathMap(
                        ImmutableSortedMap.of(
                            Paths.get("header.h"),
                            DefaultBuildTargetSourcePath.of(privateHeaderTarget)))
                    .setSymlinkTreeClass(HeaderSymlinkTree.class.getName())
                    .build())
            .build();
    assertEquals(
        expectedPrivateCxxPreprocessorInput,
        cxxLibrary.getPrivateCxxPreprocessorInput(cxxPlatform, graphBuilder));

    // Verify that we get the static archive and its build target via the NativeLinkable
    // interface.
    NativeLinkableInput expectedStaticNativeLinkableInput =
        NativeLinkableInput.of(
            ImmutableList.of(SourcePathArg.of(archive.getSourcePathToOutput())),
            ImmutableSet.of(),
            ImmutableSet.of());
    assertEquals(
        expectedStaticNativeLinkableInput,
        cxxLibrary.getNativeLinkableInput(
            cxxPlatform,
            Linker.LinkableDepType.STATIC,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE));

    // Verify that we get the static archive and its build target via the NativeLinkable
    // interface.
    NativeLinkableInput expectedSharedNativeLinkableInput =
        NativeLinkableInput.of(
            ImmutableList.of(SourcePathArg.of(sharedLibrary.getSourcePathToOutput())),
            ImmutableSet.of(),
            ImmutableSet.of());
    assertEquals(
        expectedSharedNativeLinkableInput,
        cxxLibrary.getNativeLinkableInput(
            cxxPlatform,
            Linker.LinkableDepType.SHARED,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE));

    // Verify that the implemented BuildRule methods are effectively unused.
    assertEquals(ImmutableList.<Step>of(), cxxLibrary.getBuildSteps(null, null));
    assertNull(cxxLibrary.getSourcePathToOutput());
  }

  @Test
  public void headerOnlyExports() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams params = TestBuildRuleParams.create();
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));

    BuildTarget staticPicLibraryTarget =
        target.withAppendedFlavors(
            cxxPlatform.getFlavor(), CxxDescriptionEnhancer.STATIC_PIC_FLAVOR);
    graphBuilder.addToIndex(
        new FakeBuildRule(staticPicLibraryTarget, projectFilesystem, TestBuildRuleParams.create()));

    FrameworkPath frameworkPath =
        FrameworkPath.ofSourcePath(
            DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//foo:baz")));

    // Construct a CxxLibrary object to test.
    CxxLibrary cxxLibrary =
        new CxxLibrary(
            target,
            projectFilesystem,
            params,
            CxxDeps.of(),
            CxxDeps.of(),
            /* headerOnly */ x -> true,
            (unused1, unused2) -> StringArg.from("-ldl"),
            (unused1, unused2) -> StringArg.from("-lfoobarbaz"),
            /* linkTargetInput */ (unused1, unused2, unused3, unused4) -> NativeLinkableInput.of(),
            /* supportedPlatformsRegex */ Optional.empty(),
            ImmutableSet.of(frameworkPath),
            ImmutableSet.of(),
            NativeLinkable.Linkage.STATIC,
            /* linkWhole */ false,
            Optional.empty(),
            ImmutableSortedSet.of(),
            /* isAsset */ false,
            true,
            true,
            true,
            Optional.empty());

    NativeLinkableInput expectedSharedNativeLinkableInput =
        NativeLinkableInput.of(
            StringArg.from("-ldl", "-lfoobarbaz"),
            ImmutableSet.of(frameworkPath),
            ImmutableSet.of());

    assertEquals(
        expectedSharedNativeLinkableInput,
        cxxLibrary.getNativeLinkableInput(
            cxxPlatform,
            Linker.LinkableDepType.SHARED,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE));
  }

  @Test
  public void postLinkerArgumentsExistWhenPassed() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams params = TestBuildRuleParams.create();
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));

    BuildTarget staticPicLibraryTarget =
        target.withAppendedFlavors(
            cxxPlatform.getFlavor(), CxxDescriptionEnhancer.STATIC_PIC_FLAVOR);
    graphBuilder.addToIndex(
        new FakeBuildRule(staticPicLibraryTarget, projectFilesystem, TestBuildRuleParams.create()));

    FrameworkPath frameworkPath =
        FrameworkPath.ofSourcePath(
            DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//foo:baz")));

    // Construct a CxxLibrary object to test.
    CxxLibrary cxxLibrary =
        new CxxLibrary(
            target,
            projectFilesystem,
            params,
            CxxDeps.of(),
            CxxDeps.of(),
            /* headerOnly */ x -> true,
            (unused1, unused2) -> StringArg.from("-ldl"),
            (unused1, unused2) -> StringArg.from("-lfoobarbaz"),
            /* linkTargetInput */ (unused1, unused2, unused3, unused4) -> NativeLinkableInput.of(),
            /* supportedPlatformsRegex */ Optional.empty(),
            ImmutableSet.of(frameworkPath),
            ImmutableSet.of(),
            NativeLinkable.Linkage.STATIC,
            /* linkWhole */ false,
            Optional.empty(),
            ImmutableSortedSet.of(),
            /* isAsset */ false,
            true,
            true,
            true,
            Optional.empty());

    ImmutableList.Builder<Arg> linkerArgsBuilder = ImmutableList.builder();
    linkerArgsBuilder.add(StringArg.of("-ldl"));

    ImmutableList<? extends Arg> postFlags = ImmutableList.copyOf(StringArg.from("-lfoobarbaz"));
    linkerArgsBuilder.addAll(postFlags);

    ImmutableList<Arg> linkerArgs = linkerArgsBuilder.build();

    NativeLinkableInput expectedSharedNativeLinkableInput =
        NativeLinkableInput.of(linkerArgs, ImmutableSet.of(frameworkPath), ImmutableSet.of());

    NativeLinkableInput actualSharedNativeLinkableInput =
        cxxLibrary.getNativeLinkableInput(
            cxxPlatform,
            Linker.LinkableDepType.SHARED,
            graphBuilder,
            EmptyTargetConfiguration.INSTANCE);

    assertEquals(expectedSharedNativeLinkableInput, actualSharedNativeLinkableInput);
  }
}
