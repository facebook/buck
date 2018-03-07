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

import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.android.toolchain.ndk.NdkCompilerType;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformCompiler;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper;
import com.facebook.buck.android.toolchain.ndk.impl.NdkCxxPlatforms;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.DefaultCxxPlatforms;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.testutil.ParameterizedTests;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CxxSharedLibraryInterfaceIntegrationTest {

  private static Optional<ImmutableList<Flavor>> getNdkPlatforms() {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(Paths.get(".").toAbsolutePath());

    Optional<AndroidNdk> androidNdk = AndroidNdkHelper.detectAndroidNdk(filesystem);

    if (!androidNdk.isPresent()) {
      return Optional.empty();
    }

    Path ndkDir = androidNdk.get().getNdkRootPath();
    NdkCompilerType compilerType = NdkCxxPlatforms.DEFAULT_COMPILER_TYPE;
    String ndkVersion = androidNdk.get().getNdkVersion();
    String gccVersion = NdkCxxPlatforms.getDefaultGccVersionForNdk(ndkVersion);
    String clangVersion = NdkCxxPlatforms.getDefaultClangVersionForNdk(ndkVersion);
    String compilerVersion = compilerType == NdkCompilerType.GCC ? gccVersion : clangVersion;
    NdkCxxPlatformCompiler compiler =
        NdkCxxPlatformCompiler.builder()
            .setType(compilerType)
            .setVersion(compilerVersion)
            .setGccVersion(gccVersion)
            .build();
    ImmutableMap<TargetCpuType, NdkCxxPlatform> ndkPlatforms =
        NdkCxxPlatforms.getPlatforms(
            new CxxBuckConfig(FakeBuckConfig.builder().build()),
            new AndroidBuckConfig(FakeBuckConfig.builder().build(), Platform.detect()),
            filesystem,
            ndkDir,
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

  @Parameterized.Parameters(name = "type={0},platform={1}")
  public static Collection<Object[]> data() throws InterruptedException {
    List<Flavor> platforms = new ArrayList<>();

    // Test the system platform.
    if (Platform.detect().equals(Platform.LINUX)) {
      platforms.add(DefaultCxxPlatforms.FLAVOR);
    }

    // If the NDK is present, test it's platforms.
    Optional<ImmutableList<Flavor>> ndkPlatforms = getNdkPlatforms();
    ndkPlatforms.ifPresent(platforms::addAll);

    return ParameterizedTests.getPermutations(
        ImmutableList.of("cxx_library", "prebuilt_cxx_library"),
        platforms,
        /* independentInterfaces */ ImmutableList.of(true, false));
  }

  @Parameterized.Parameter public String type;

  @Parameterized.Parameter(value = 1)
  public Flavor platform;

  @Parameterized.Parameter(value = 2)
  public boolean independentInterfaces;

  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private BuildTarget sharedBinaryTarget;
  private BuildTarget sharedBinaryBuiltTarget;
  private BuildTarget staticBinaryTarget;
  private BuildTarget staticBinaryBuiltTarget;
  private Optional<BuildTarget> sharedLibraryTarget;

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "shared_library_interfaces", tmp);
    workspace.setUp();
    staticBinaryTarget =
        BuildTargetFactory.newInstance("//:static_binary_" + type).withAppendedFlavors(platform);
    staticBinaryBuiltTarget =
        staticBinaryTarget.withAppendedFlavors(CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR);
    sharedBinaryTarget =
        BuildTargetFactory.newInstance("//:shared_binary_" + type).withAppendedFlavors(platform);
    sharedBinaryBuiltTarget =
        sharedBinaryTarget.withAppendedFlavors(CxxDescriptionEnhancer.CXX_LINK_BINARY_FLAVOR);
    sharedLibraryTarget =
        !type.equals("cxx_library")
            ? Optional.empty()
            : Optional.of(
                CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
                    BuildTargetFactory.newInstance("//:" + type),
                    platform,
                    Linker.LinkType.SHARED));
  }

  @Test
  public void sharedInterfaceLibraryPreventsRebuildAfterNonLocalVarNameChange() throws IOException {
    BuckBuildLog log;

    // First verify that *not* using shared library interfaces causes a rebuild even after making a
    // non-interface change.
    ImmutableList<String> args =
        ImmutableList.of(
            "-c",
            "cxx.shlib_interfaces=disabled",
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            sharedBinaryTarget.getFullyQualifiedName());
    String[] argv = args.toArray(new String[args.size()]);
    workspace.runBuckBuild(argv).assertSuccess();
    assertTrue(workspace.replaceFileContents("library.cpp", "bar1", "bar2"));
    workspace.runBuckBuild(argv).assertSuccess();
    log = workspace.getBuildLog();
    if (sharedLibraryTarget.isPresent()) {
      log.assertTargetBuiltLocally(sharedLibraryTarget.get().toString());
    }
    log.assertTargetBuiltLocally(sharedBinaryBuiltTarget.toString());

    // Now verify that using shared library interfaces does not cause a rebuild after making a
    // non-interface change.
    ImmutableList<String> iArgs =
        ImmutableList.of(
            "-c",
            "cxx.shlib_interfaces=enabled",
            "-c",
            "cxx.independent_shlib_interfaces=" + independentInterfaces,
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            sharedBinaryTarget.getFullyQualifiedName());
    String[] iArgv = iArgs.toArray(new String[iArgs.size()]);
    workspace.runBuckBuild(iArgv).assertSuccess();
    assertTrue(workspace.replaceFileContents("library.cpp", "bar2", "bar3"));
    workspace.runBuckBuild(iArgv).assertSuccess();
    log = workspace.getBuildLog();
    if (sharedLibraryTarget.isPresent()) {
      log.assertTargetBuiltLocally(sharedLibraryTarget.get().toString());
    }
    log.assertTargetHadMatchingInputRuleKey(sharedBinaryBuiltTarget.toString());
  }

  @Test
  public void sharedInterfaceLibraryPreventsRebuildAfterCodeChange() throws IOException {
    BuckBuildLog log;

    // First verify that *not* using shared library interfaces causes a rebuild even after making a
    // non-interface change.
    ImmutableList<String> args =
        ImmutableList.of(
            "-c",
            "cxx.shlib_interfaces=disabled",
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            sharedBinaryTarget.getFullyQualifiedName());
    String[] argv = args.toArray(new String[args.size()]);
    workspace.runBuckBuild(argv).assertSuccess();
    assertTrue(workspace.replaceFileContents("library.cpp", "bar1 = 0", "bar1 = 1"));
    workspace.runBuckBuild(argv).assertSuccess();
    log = workspace.getBuildLog();
    if (sharedLibraryTarget.isPresent()) {
      log.assertTargetBuiltLocally(sharedLibraryTarget.get().toString());
    }
    log.assertTargetBuiltLocally(sharedBinaryBuiltTarget.toString());

    // Now verify that using shared library interfaces does not cause a rebuild after making a
    // non-interface change.
    ImmutableList<String> iArgs =
        ImmutableList.of(
            "-c",
            "cxx.shlib_interfaces=enabled",
            "-c",
            "cxx.independent_shlib_interfaces=" + independentInterfaces,
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            sharedBinaryTarget.getFullyQualifiedName());
    String[] iArgv = iArgs.toArray(new String[iArgs.size()]);
    workspace.runBuckBuild(iArgv).assertSuccess();
    assertTrue(workspace.replaceFileContents("library.cpp", "bar1 = 1", "bar1 = 2"));
    workspace.runBuckBuild(iArgv).assertSuccess();
    log = workspace.getBuildLog();
    if (sharedLibraryTarget.isPresent()) {
      log.assertTargetBuiltLocally(sharedLibraryTarget.get().toString());
    }
    log.assertTargetHadMatchingInputRuleKey(sharedBinaryBuiltTarget.toString());
  }

  @Test
  public void sharedInterfaceLibraryPreventsRebuildAfterAddedCode() throws IOException {
    BuckBuildLog log;

    // First verify that *not* using shared library interfaces causes a rebuild even after making a
    // non-interface change.
    ImmutableList<String> args =
        ImmutableList.of(
            "-c",
            "cxx.shlib_interfaces=disabled",
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            sharedBinaryTarget.getFullyQualifiedName());
    String[] argv = args.toArray(new String[args.size()]);
    workspace.runBuckBuild(argv).assertSuccess();
    assertTrue(workspace.replaceFileContents("library.cpp", "return bar1", "return bar1 += 15"));
    workspace.runBuckBuild(argv).assertSuccess();
    log = workspace.getBuildLog();
    if (sharedLibraryTarget.isPresent()) {
      log.assertTargetBuiltLocally(sharedLibraryTarget.get().toString());
    }
    log.assertTargetBuiltLocally(sharedBinaryBuiltTarget.toString());

    // Revert changes.
    assertTrue(workspace.replaceFileContents("library.cpp", "return bar1 += 15", "return bar1"));

    // Now verify that using shared library interfaces does not cause a rebuild after making a
    // non-interface change.
    ImmutableList<String> iArgs =
        ImmutableList.of(
            "-c",
            "cxx.shlib_interfaces=enabled",
            "-c",
            "cxx.independent_shlib_interfaces=" + independentInterfaces,
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            sharedBinaryTarget.getFullyQualifiedName());
    String[] iArgv = iArgs.toArray(new String[iArgs.size()]);
    workspace.runBuckBuild(iArgv).assertSuccess();
    assertTrue(workspace.replaceFileContents("library.cpp", "return bar1", "return bar1 += 15"));
    workspace.runBuckBuild(iArgv).assertSuccess();
    log = workspace.getBuildLog();
    if (sharedLibraryTarget.isPresent()) {
      log.assertTargetBuiltLocally(sharedLibraryTarget.get().toString());
    }
    log.assertTargetHadMatchingInputRuleKey(sharedBinaryBuiltTarget.toString());
  }

  @Test
  public void sharedInterfaceLibraryDoesRebuildAfterInterfaceChange() throws IOException {
    ImmutableList<String> args =
        ImmutableList.of(
            "-c",
            "cxx.shlib_interfaces=enabled",
            "-c",
            "cxx.independent_shlib_interfaces=" + independentInterfaces,
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            sharedBinaryTarget.getFullyQualifiedName());
    String[] argv = args.toArray(new String[args.size()]);
    workspace.runBuckBuild(argv).assertSuccess();
    assertTrue(workspace.replaceFileContents("library.cpp", "foo", "bar"));
    workspace.runBuckBuild(argv).assertFailure();
  }

  @Test
  public void sharedInterfaceLibraryDoesNotAffectStaticLinking() throws IOException {
    BuckBuildLog log;

    // Verify that using shared library interfaces does not affect static linking.
    ImmutableList<String> iArgs =
        ImmutableList.of(
            "-c",
            "cxx.shlib_interfaces=enabled",
            "-c",
            "cxx.independent_shlib_interfaces=" + independentInterfaces,
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            staticBinaryTarget.getFullyQualifiedName());
    String[] iArgv = iArgs.toArray(new String[iArgs.size()]);
    workspace.runBuckBuild(iArgv).assertSuccess();
    assertTrue(workspace.replaceFileContents("library.cpp", "bar1", "bar2"));
    workspace.runBuckBuild(iArgv).assertSuccess();
    log = workspace.getBuildLog();
    log.assertTargetBuiltLocally(staticBinaryBuiltTarget.toString());
  }

  @Test
  public void sharedInterfaceLibraryPreventsRebuildAfterAddedUndefinedSymbol() throws IOException {
    BuckBuildLog log;
    String originalContents = workspace.getFileContents("library.cpp");

    // First verify that *not* using shared library interfaces causes a rebuild even after making a
    // non-interface change.
    ImmutableList<String> args =
        ImmutableList.of(
            "-c",
            "cxx.shlib_interfaces=disabled",
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            sharedBinaryTarget.getFullyQualifiedName());
    String[] argv = args.toArray(new String[args.size()]);
    workspace.runBuckBuild(argv).assertSuccess();
    assertTrue(workspace.replaceFileContents("library.cpp", "return bar1", "return bar1 + bar2"));
    workspace.runBuckBuild(argv).assertSuccess();
    log = workspace.getBuildLog();
    if (sharedLibraryTarget.isPresent()) {
      log.assertTargetBuiltLocally(sharedLibraryTarget.get().toString());
    }
    log.assertTargetBuiltLocally(sharedBinaryBuiltTarget.toString());

    // Revert changes.
    workspace.writeContentsToPath(originalContents, "library.cpp");

    // Now verify that using shared library interfaces does not cause a rebuild after making a
    // non-interface change.
    ImmutableList<String> iArgs =
        ImmutableList.of(
            "-c",
            "cxx.shlib_interfaces=defined_only",
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            sharedBinaryTarget.getFullyQualifiedName());
    String[] iArgv = iArgs.toArray(new String[iArgs.size()]);
    workspace.runBuckBuild(iArgv).assertSuccess();
    assertTrue(workspace.replaceFileContents("library.cpp", "return bar1", "return bar1 + bar2"));
    workspace.runBuckBuild(iArgv).assertSuccess();
    log = workspace.getBuildLog();
    if (sharedLibraryTarget.isPresent()) {
      log.assertTargetBuiltLocally(sharedLibraryTarget.get().toString());
    }
    log.assertTargetHadMatchingInputRuleKey(sharedBinaryBuiltTarget.toString());
  }
}
