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

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.DefaultAndroidDirectoryResolver;
import com.facebook.buck.android.NdkCxxPlatform;
import com.facebook.buck.android.NdkCxxPlatformCompiler;
import com.facebook.buck.android.NdkCxxPlatforms;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.testutil.ParameterizedTests;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
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

  private static Optional<ImmutableList<Flavor>> getNdkPlatforms() throws InterruptedException {
    ProjectFilesystem filesystem = new ProjectFilesystem(Paths.get(".").toAbsolutePath());
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
            new AndroidBuckConfig(FakeBuckConfig.builder().build(), Platform.detect()),
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
        ImmutableList.of("cxx_library", "prebuilt_cxx_library"), platforms);
  }

  @Parameterized.Parameter public String type;

  @Parameterized.Parameter(value = 1)
  public Flavor platform;

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
            "cxx.shared_library_interfaces=false",
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            sharedBinaryTarget.getFullyQualifiedName());
    String[] argv = args.toArray(new String[args.size()]);
    workspace.runBuckBuild(argv).assertSuccess();
    workspace.replaceFileContents("library.cpp", "bar1", "bar2");
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
            "cxx.shared_library_interfaces=true",
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            sharedBinaryTarget.getFullyQualifiedName());
    String[] iArgv = iArgs.toArray(new String[iArgs.size()]);
    workspace.runBuckBuild(iArgv).assertSuccess();
    workspace.replaceFileContents("library.cpp", "bar2", "bar3");
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
            "cxx.shared_library_interfaces=false",
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            sharedBinaryTarget.getFullyQualifiedName());
    String[] argv = args.toArray(new String[args.size()]);
    workspace.runBuckBuild(argv).assertSuccess();
    workspace.replaceFileContents("library.cpp", "bar1 = 0", "bar1 = 1");
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
            "cxx.shared_library_interfaces=true",
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            sharedBinaryTarget.getFullyQualifiedName());
    String[] iArgv = iArgs.toArray(new String[iArgs.size()]);
    workspace.runBuckBuild(iArgv).assertSuccess();
    workspace.replaceFileContents("library.cpp", "bar1 = 1", "bar1 = 2");
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
            "cxx.shared_library_interfaces=false",
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            sharedBinaryTarget.getFullyQualifiedName());
    String[] argv = args.toArray(new String[args.size()]);
    workspace.runBuckBuild(argv).assertSuccess();
    workspace.replaceFileContents("library.cpp", "return bar1", "return bar1 += 15");
    workspace.runBuckBuild(argv).assertSuccess();
    log = workspace.getBuildLog();
    if (sharedLibraryTarget.isPresent()) {
      log.assertTargetBuiltLocally(sharedLibraryTarget.get().toString());
    }
    log.assertTargetBuiltLocally(sharedBinaryBuiltTarget.toString());

    // Revert changes.
    workspace.replaceFileContents("library.cpp", "return bar1 += 15", "return bar1");

    // Now verify that using shared library interfaces does not cause a rebuild after making a
    // non-interface change.
    ImmutableList<String> iArgs =
        ImmutableList.of(
            "-c",
            "cxx.shared_library_interfaces=true",
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            sharedBinaryTarget.getFullyQualifiedName());
    String[] iArgv = iArgs.toArray(new String[iArgs.size()]);
    workspace.runBuckBuild(iArgv).assertSuccess();
    workspace.replaceFileContents("library.cpp", "return bar1", "return bar1 += 15");
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
            "cxx.shared_library_interfaces=true",
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            sharedBinaryTarget.getFullyQualifiedName());
    String[] argv = args.toArray(new String[args.size()]);
    workspace.runBuckBuild(argv).assertSuccess();
    workspace.replaceFileContents("library.cpp", "foo", "bar");
    workspace.runBuckBuild(argv).assertFailure();
  }

  @Test
  public void sharedInterfaceLibraryDoesNotAffectStaticLinking() throws IOException {
    BuckBuildLog log;

    // Verify that using shared library interfaces does not affect static linking.
    ImmutableList<String> iArgs =
        ImmutableList.of(
            "-c",
            "cxx.shared_library_interfaces=true",
            "-c",
            "cxx.objcopy=/usr/bin/objcopy",
            "-c",
            "cxx.platform=" + platform,
            staticBinaryTarget.getFullyQualifiedName());
    String[] iArgv = iArgs.toArray(new String[iArgs.size()]);
    workspace.runBuckBuild(iArgv).assertSuccess();
    workspace.replaceFileContents("library.cpp", "bar1", "bar2");
    workspace.runBuckBuild(iArgv).assertSuccess();
    log = workspace.getBuildLog();
    log.assertTargetBuiltLocally(staticBinaryBuiltTarget.toString());
  }
}
