/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.android.DefaultAndroidDirectoryResolver;
import com.facebook.buck.android.NdkCxxPlatform;
import com.facebook.buck.android.NdkCxxPlatformCompiler;
import com.facebook.buck.android.NdkCxxPlatforms;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RunWith(Parameterized.class)
public class CxxSharedLibraryInterfaceIntegrationTest {

  private static Optional<ImmutableList<Flavor>> getNdkPlatforms() {
    ProjectFilesystem filesystem =
        new ProjectFilesystem(Paths.get(".").toAbsolutePath());
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            filesystem.getRootPath().getFileSystem(),
            ImmutableMap.copyOf(System.getenv()),
            Optional.empty(),
            Optional.empty());
    Optional<Path> ndkDir = resolver.getNdkOrAbsent();
    if (!ndkDir.isPresent()) {
      return Optional.empty();
    }
    NdkCxxPlatformCompiler.Type compilerType = NdkCxxPlatforms.DEFAULT_COMPILER_TYPE;
    Optional<String> ndkVersion = resolver.getNdkVersion();
    String gccVersion = NdkCxxPlatforms.getDefaultGccVersionForNdk(ndkVersion);
    String clangVersion = NdkCxxPlatforms.getDefaultClangVersionForNdk(ndkVersion);
    String compilerVersion =
        compilerType == NdkCxxPlatformCompiler.Type.GCC ? gccVersion : clangVersion;
    NdkCxxPlatformCompiler compiler =
        NdkCxxPlatformCompiler.builder()
            .setType(compilerType)
            .setVersion(compilerVersion)
            .setGccVersion(gccVersion)
            .build();
    ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> ndkPlatforms =
        NdkCxxPlatforms.getPlatforms(
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            filesystem,
            ndkDir.get(),
            compiler,
            NdkCxxPlatforms.DEFAULT_CXX_RUNTIME,
            NdkCxxPlatforms.DEFAULT_TARGET_APP_PLATFORM,
            NdkCxxPlatforms.DEFAULT_CPU_ABIS,
            Platform.detect());
    // Just return one of the NDK platforms, which should be enough to test shared library interface
    // functionality.
    return Optional.of(
        ImmutableList.of(ndkPlatforms.values().iterator().next().getCxxPlatform().getFlavor()));
  }

  @Parameterized.Parameters(name = "platform={0}")
  public static Collection<Object[]> data() {
    List<Flavor> platforms = new ArrayList<>();

    // Test the system platform.
    if (Platform.detect().equals(Platform.LINUX)) {
      platforms.add(DefaultCxxPlatforms.FLAVOR);
    }

    // If the NDK is present, test it's platforms.
    Optional<ImmutableList<Flavor>> ndkPlatforms = getNdkPlatforms();
    ndkPlatforms.ifPresent(platforms::addAll);

    return RichStream.from(platforms)
        .map(f -> new Object[]{f})
        .toImmutableList();
  }

  @Parameterized.Parameter
  public Flavor platform;

  private ProjectWorkspace workspace;

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "shared_library", tmp);
    workspace.setUp();
  }

  @Test
  public void sharedInterfaceLibraryPreventsRebuildAfterNonLocalVarNameChange() throws IOException {
    BuildTarget binaryTarget =
        CxxDescriptionEnhancer.createCxxLinkTarget(
            BuildTargetFactory.newInstance("//:binary"),
            Optional.empty())
            .withAppendedFlavors(platform);
    BuildTarget libraryTarget =
        CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
            BuildTargetFactory.newInstance("//subdir:library"),
            platform,
            Linker.LinkType.SHARED);
    BuckBuildLog log;

    // First verify that *not* using shared library interfaces causes a rebuild even after making a
    // non-interface change.
    ImmutableList<String> args =
        ImmutableList.of(
            "-c", "cxx.shared_library_interfaces=false",
            "-c", "cxx.objcopy=/usr/bin/objcopy",
            "//:binary#" + platform);
    String[] argv = args.toArray(new String[args.size()]);
    workspace.runBuckBuild(argv).assertSuccess();
    workspace.replaceFileContents("subdir/library.cpp", "bar1", "bar2");
    workspace.runBuckBuild(argv).assertSuccess();
    log = workspace.getBuildLog();
    log.assertTargetBuiltLocally(libraryTarget.toString());
    log.assertTargetBuiltLocally(binaryTarget.toString());

    // Now verify that using shared library interfaces does not cause a rebuild after making a
    // non-interface change.
    ImmutableList<String> iArgs =
        ImmutableList.of(
            "-c", "cxx.shared_library_interfaces=true",
            "-c", "cxx.objcopy=/usr/bin/objcopy",
            "//:binary#" + platform);
    String[] iArgv = iArgs.toArray(new String[iArgs.size()]);
    workspace.runBuckBuild(iArgv).assertSuccess();
    workspace.replaceFileContents("subdir/library.cpp", "bar2", "bar3");
    workspace.runBuckBuild(iArgv).assertSuccess();
    log = workspace.getBuildLog();
    log.assertTargetBuiltLocally(libraryTarget.toString());
    log.assertTargetHadMatchingInputRuleKey(binaryTarget.toString());
  }

  @Test
  public void sharedInterfaceLibraryPreventsRebuildAfterCodeChange() throws IOException {
    BuildTarget libraryTarget =
        CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
            BuildTargetFactory.newInstance("//subdir:library"),
            platform,
            Linker.LinkType.SHARED);
    BuildTarget binaryTarget =
        CxxDescriptionEnhancer.createCxxLinkTarget(
            BuildTargetFactory.newInstance("//:binary"),
            Optional.empty())
            .withAppendedFlavors(platform);
    BuckBuildLog log;

    // First verify that *not* using shared library interfaces causes a rebuild even after making a
    // non-interface change.
    ImmutableList<String> args =
        ImmutableList.of(
            "-c", "cxx.shared_library_interfaces=false",
            "-c", "cxx.objcopy=/usr/bin/objcopy",
            "//:binary#" + platform);
    String[] argv = args.toArray(new String[args.size()]);
    workspace.runBuckBuild(argv).assertSuccess();
    workspace.replaceFileContents("subdir/library.cpp", "bar1 = 0", "bar1 = 1");
    workspace.runBuckBuild(argv).assertSuccess();
    log = workspace.getBuildLog();
    log.assertTargetBuiltLocally(libraryTarget.toString());
    log.assertTargetBuiltLocally(binaryTarget.toString());

    // Now verify that using shared library interfaces does not cause a rebuild after making a
    // non-interface change.
    ImmutableList<String> iArgs =
        ImmutableList.of(
            "-c", "cxx.shared_library_interfaces=true",
            "-c", "cxx.objcopy=/usr/bin/objcopy",
            "//:binary#" + platform);
    String[] iArgv = iArgs.toArray(new String[iArgs.size()]);
    workspace.runBuckBuild(iArgv).assertSuccess();
    workspace.replaceFileContents("subdir/library.cpp", "bar1 = 1", "bar1 = 2");
    workspace.runBuckBuild(iArgv).assertSuccess();
    log = workspace.getBuildLog();
    log.assertTargetBuiltLocally(libraryTarget.toString());
    log.assertTargetHadMatchingInputRuleKey(binaryTarget.toString());
  }

  @Test
  public void sharedInterfaceLibraryPreventsRebuildAfterAddedCode() throws IOException {
    BuildTarget libraryTarget =
        CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
            BuildTargetFactory.newInstance("//subdir:library"),
            platform,
            Linker.LinkType.SHARED);
    BuildTarget binaryTarget =
        CxxDescriptionEnhancer.createCxxLinkTarget(
            BuildTargetFactory.newInstance("//:binary"),
            Optional.empty())
            .withAppendedFlavors(platform);
    BuckBuildLog log;

    // First verify that *not* using shared library interfaces causes a rebuild even after making a
    // non-interface change.
    ImmutableList<String> args =
        ImmutableList.of(
            "-c", "cxx.shared_library_interfaces=false",
            "-c", "cxx.objcopy=/usr/bin/objcopy",
            "//:binary#" + platform);
    String[] argv = args.toArray(new String[args.size()]);
    workspace.runBuckBuild(argv).assertSuccess();
    workspace.replaceFileContents("subdir/library.cpp", "return bar1", "return bar1 += 15");
    workspace.runBuckBuild(argv).assertSuccess();
    log = workspace.getBuildLog();
    log.assertTargetBuiltLocally(libraryTarget.toString());
    log.assertTargetBuiltLocally(binaryTarget.toString());

    // Revert changes.
    workspace.replaceFileContents("subdir/library.cpp", "return bar1 += 15", "return bar1");

    // Now verify that using shared library interfaces does not cause a rebuild after making a
    // non-interface change.
    ImmutableList<String> iArgs =
        ImmutableList.of(
            "-c", "cxx.shared_library_interfaces=true",
            "-c", "cxx.objcopy=/usr/bin/objcopy",
            "//:binary#" + platform);
    String[] iArgv = iArgs.toArray(new String[iArgs.size()]);
    workspace.runBuckBuild(iArgv).assertSuccess();
    workspace.replaceFileContents("subdir/library.cpp", "return bar1", "return bar1 += 15");
    workspace.runBuckBuild(iArgv).assertSuccess();
    log = workspace.getBuildLog();
    log.assertTargetBuiltLocally(libraryTarget.toString());
    log.assertTargetHadMatchingInputRuleKey(binaryTarget.toString());
  }

  @Test
  public void sharedInterfaceLibraryDoesRebuildAfterInterfaceChange() throws IOException {
    ImmutableList<String> args =
        ImmutableList.of(
            "-c", "cxx.shared_library_interfaces=true",
            "-c", "cxx.objcopy=/usr/bin/objcopy",
            "//:binary#" + platform);
    String[] argv = args.toArray(new String[args.size()]);
    workspace.runBuckBuild(argv).assertSuccess();
    workspace.replaceFileContents("subdir/library.cpp", "foo", "bar");
    workspace.runBuckBuild(argv).assertFailure();
  }

  @Test
  public void sharedInterfaceLibraryDoesNotAffectStaticLinking() throws IOException {
    BuildTarget binaryTarget =
        CxxDescriptionEnhancer.createCxxLinkTarget(
            BuildTargetFactory.newInstance("//:static_binary"),
            Optional.empty())
            .withAppendedFlavors(platform);
    BuildTarget libraryTarget =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            BuildTargetFactory.newInstance("//subdir:library"),
            platform,
            CxxSourceRuleFactory.PicType.PDC);
    BuckBuildLog log;

    // Verify that using shared library interfaces does not affect static linking.
    ImmutableList<String> iArgs =
        ImmutableList.of(
            "-c", "cxx.shared_library_interfaces=true",
            "-c", "cxx.objcopy=/usr/bin/objcopy",
            "//:static_binary#" + platform);
    String[] iArgv = iArgs.toArray(new String[iArgs.size()]);
    workspace.runBuckBuild(iArgv).assertSuccess();
    workspace.replaceFileContents("subdir/library.cpp", "bar1", "bar2");
    workspace.runBuckBuild(iArgv).assertSuccess();
    log = workspace.getBuildLog();
    log.assertTargetBuiltLocally(libraryTarget.toString());
    log.assertTargetBuiltLocally(binaryTarget.toString());
  }

}
