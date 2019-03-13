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

package com.facebook.buck.android.toolchain.ndk.impl;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.android.toolchain.ndk.NdkCompilerType;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformCompiler;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformTargetConfiguration;
import com.facebook.buck.android.toolchain.ndk.NdkCxxRuntime;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.android.toolchain.ndk.impl.NdkCxxPlatforms.Host;
import com.facebook.buck.android.toolchain.ndk.impl.NdkCxxPlatforms.NdkCxxToolchainPaths;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Result;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class NdkCxxPlatformIntegrationTest {

  @Parameterized.Parameters(name = "{0},{1},{2}")
  public static Collection<Object[]> data() {
    ImmutableList.Builder<TargetCpuType> targetCpuTypes = ImmutableList.builder();
    if (AssumeAndroidPlatform.isArmAvailable()) {
      targetCpuTypes.add(TargetCpuType.ARM);
    }
    targetCpuTypes.add(
        TargetCpuType.ARMV7, TargetCpuType.ARM64, TargetCpuType.X86, TargetCpuType.X86_64);
    List<Object[]> data = new ArrayList<>();
    for (TargetCpuType targetCpuType : targetCpuTypes.build()) {
      if (AssumeAndroidPlatform.isGnuStlAvailable()) {
        data.add(new Object[] {NdkCompilerType.GCC, NdkCxxRuntime.GNUSTL, targetCpuType});
        // We don't support 64-bit clang yet.
        if (targetCpuType != TargetCpuType.ARM64 && targetCpuType != TargetCpuType.X86_64) {
          data.add(new Object[] {NdkCompilerType.CLANG, NdkCxxRuntime.GNUSTL, targetCpuType});
          data.add(new Object[] {NdkCompilerType.CLANG, NdkCxxRuntime.LIBCXX, targetCpuType});
        }
      } else {
        data.add(new Object[] {NdkCompilerType.CLANG, NdkCxxRuntime.LIBCXX, targetCpuType});
      }
    }
    return data;
  }

  @Parameterized.Parameter public NdkCompilerType compiler;

  @Parameterized.Parameter(value = 1)
  public NdkCxxRuntime cxxRuntime;

  @Parameterized.Parameter(value = 2)
  public TargetCpuType targetCpuType;

  @Rule public TemporaryPaths tmp = new TemporaryPaths("ndk-test", true);
  @Rule public TemporaryPaths tmp_long_pwd = new TemporaryPaths("ndk-test-long-pwd", true);

  private String architectures;

  private ProjectWorkspace setupWorkspace(String name, TemporaryPaths tmp) throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, name, tmp);
    workspace.setUp();

    workspace.writeContentsToPath(
        String.format(
            "[ndk]\n"
                + "  compiler = %s\n"
                + "  gcc_version = 4.9\n"
                + "  cxx_runtime = %s\n"
                + "  cpu_abis = "
                + architectures
                + "\n"
                + "  app_platform = android-21\n",
            compiler,
            cxxRuntime),
        ".buckconfig");
    return workspace;
  }

  private Path getNdkRoot() {
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(Paths.get(".").toAbsolutePath());
    Path ndkDir = AndroidNdkHelper.detectAndroidNdk(projectFilesystem).get().getNdkRootPath();
    assertTrue(Files.exists(ndkDir));
    return ndkDir;
  }

  private String getTargetCpuType() {
    return targetCpuType.name().toLowerCase();
  }

  @Before
  public void setUp() {
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    if (AssumeAndroidPlatform.isArmAvailable()) {
      architectures = "arm, armv7, arm64, x86, x86_64";
    } else {
      architectures = "armv7, arm64, x86, x86_64";
    }
  }

  @Test
  public void runtimeSupportsStl() throws IOException {
    assumeTrue(
        "libcxx is unsupported with this ndk",
        NdkCxxPlatforms.isSupportedConfiguration(getNdkRoot(), cxxRuntime));
    ProjectWorkspace workspace = setupWorkspace("runtime_stl", tmp);
    workspace
        .runBuckCommand("build", String.format("//:main#android-%s", getTargetCpuType()))
        .assertSuccess();
  }

  @Test
  public void changedPlatformTarget() throws IOException {
    assumeTrue(
        "libcxx is unsupported with this ndk",
        NdkCxxPlatforms.isSupportedConfiguration(getNdkRoot(), cxxRuntime));
    // 64-bit only works with platform 21, so we can't change the platform to anything else.
    assumeThat(
        "skip this test for 64-bit, for now",
        targetCpuType,
        not(anyOf(equalTo(TargetCpuType.ARM64), equalTo(TargetCpuType.X86_64))));

    ProjectWorkspace workspace = setupWorkspace("ndk_app_platform", tmp);

    BuildTarget target =
        BuildTargetFactory.newInstance(String.format("//:main#android-%s", getTargetCpuType()));
    BuildTarget linkTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target, Optional.empty());

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(linkTarget);

    // Change the app platform and verify that our rulekey has changed.
    workspace.writeContentsToPath("[ndk]\n  app_platform = android-17", ".buckconfig");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(linkTarget);
  }

  @Test
  public void testWorkingDirectoryAndNdkHeaderPathsAreSanitized() throws Exception {
    // TODO: fix for Clang
    assumeTrue("clang is not supported", compiler != NdkCompilerType.CLANG);
    ProjectWorkspace workspace = setupWorkspace("ndk_debug_paths", tmp);
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            String.format("//:lib#android-%s,static", getTargetCpuType()));
    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    Path lib =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem, target, "%s/lib" + target.getShortName() + ".a"));
    String contents = MorePaths.asByteSource(lib).asCharSource(Charsets.ISO_8859_1).read();

    // Verify that the working directory is sanitized.
    assertLibraryArchiveContentDoesNotContain(
        workspace,
        filesystem,
        lib,
        ImmutableSet.of("test.c.o", "test.cpp.o", "test.s.o"),
        tmp.getRoot().toString());

    // Verify that we don't have any references to the build toolchain in the debug info.
    for (NdkCxxPlatforms.Host host : NdkCxxPlatforms.Host.values()) {
      assertFalse(contents.contains(host.toString()));
    }

    // Verify that the NDK path is sanitized.
    assertFalse(contents.contains(getNdkRoot().toString()));

    // Run another build in a location with a longer PWD, to verify that this doesn't affect output.
    ProjectWorkspace longPwdWorkspace = setupWorkspace("ndk_debug_paths", tmp_long_pwd);
    ProjectFilesystem longPwdFilesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    longPwdWorkspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    lib =
        longPwdWorkspace.getPath(
            BuildTargetPaths.getGenPath(
                longPwdFilesystem, target, "%s/lib" + target.getShortName() + ".a"));
    String movedContents = MorePaths.asByteSource(lib).asCharSource(Charsets.ISO_8859_1).read();
    assertEquals(contents, movedContents);
  }

  private void assertLibraryArchiveContentDoesNotContain(
      ProjectWorkspace workspace,
      ProjectFilesystem projectFilesystem,
      Path libPath,
      ImmutableSet<String> expectedEntries,
      String content)
      throws Exception {

    NdkCxxToolchainPaths ndkCxxToolchainPaths =
        createNdkCxxToolchainPaths(workspace, projectFilesystem);
    Path arToolPath = ndkCxxToolchainPaths.getToolchainBinPath().resolve("ar");
    Path extractedLibraryPath = tmp.newFolder("extracted-library");

    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of(arToolPath.toString(), "-x", libPath.toString()))
            .setDirectory(extractedLibraryPath)
            .build();
    Result arExecResult = new DefaultProcessExecutor(new TestConsole()).launchAndExecute(params);
    assertEquals(
        String.format(
            "ar failed.\nstdout:\n%sstderr:\n%s",
            arExecResult.getStdout(), arExecResult.getStderr()),
        0,
        arExecResult.getExitCode());

    ImmutableSet<Path> extractedFiles =
        Files.list(extractedLibraryPath).collect(ImmutableSet.toImmutableSet());

    assertEquals(expectedEntries, expectedEntries);
    for (Path extractedFile : extractedFiles) {
      makeFileReadable(extractedFile);
      assertFalse(
          dumpObjectFile(ndkCxxToolchainPaths.getToolchainBinPath(), extractedFile)
              .contains(content));
    }
  }

  private NdkCxxToolchainPaths createNdkCxxToolchainPaths(
      ProjectWorkspace workspace, ProjectFilesystem projectFilesystem)
      throws IOException, InterruptedException {
    Optional<AndroidNdk> androidNdk = AndroidNdkHelper.detectAndroidNdk(projectFilesystem);
    assumeTrue(androidNdk.isPresent());
    String ndkVersion = androidNdk.get().getNdkVersion();
    BuckConfig buckConfig = workspace.asCell().getBuckConfig();
    AndroidBuckConfig androidConfig = new AndroidBuckConfig(buckConfig, Platform.detect());
    String androidPlatform =
        androidConfig
            .getNdkAppPlatformForCpuAbi(getTargetCpuType())
            .orElse(NdkCxxPlatforms.DEFAULT_TARGET_APP_PLATFORM);
    String gccVersion =
        androidConfig
            .getNdkGccVersion()
            .orElse(NdkCxxPlatforms.getDefaultGccVersionForNdk(ndkVersion));
    NdkCxxPlatformCompiler ndkCxxPlatformCompiler =
        NdkCxxPlatformCompiler.builder()
            .setType(NdkCompilerType.GCC)
            .setVersion(gccVersion)
            .setGccVersion(gccVersion)
            .build();
    NdkCxxPlatformTargetConfiguration targetConfiguration =
        NdkCxxPlatforms.getTargetConfiguration(
            targetCpuType, ndkCxxPlatformCompiler, androidPlatform);
    Host host = NdkCxxPlatforms.getHost(Platform.detect());
    return new NdkCxxToolchainPaths(
        projectFilesystem,
        androidNdk.get().getNdkRootPath(),
        targetConfiguration,
        host.toString(),
        cxxRuntime,
        false,
        NdkCxxPlatforms.getUseUnifiedHeaders(androidConfig, ndkVersion));
  }

  private void makeFileReadable(Path file) throws Exception {
    if (Platform.detect() == Platform.WINDOWS) {
      return;
    }
    Files.setPosixFilePermissions(file, EnumSet.of(OWNER_READ));
  }

  private String dumpObjectFile(Path toolchainBinPath, Path file) throws Exception {
    Path objdumpToolPath = toolchainBinPath.resolve("objdump");
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(objdumpToolPath.toString(), "-g", file.getFileName().toString()))
            .setDirectory(file.getParent())
            .build();
    Result objdumpExecResult =
        new DefaultProcessExecutor(new TestConsole()).launchAndExecute(params);
    assertEquals(
        String.format(
            "objdump failed.\nstdout:\n%sstderr:\n%s",
            objdumpExecResult.getStdout(), objdumpExecResult.getStderr()),
        0,
        objdumpExecResult.getExitCode());
    return objdumpExecResult.getStdout().get();
  }
}
