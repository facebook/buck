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

package com.facebook.buck.android;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Charsets;
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
public class NdkCxxPlatformIntegrationTest {

  @Parameterized.Parameters(name = "{0},{1},{2}")
  public static Collection<Object[]> data() {
    List<Object[]> data = new ArrayList<>();
    for (String arch : ImmutableList.of("arm", "armv7", "arm64", "x86", "x86_64")) {
      data.add(new Object[] {NdkCxxPlatformCompiler.Type.GCC, NdkCxxRuntime.GNUSTL, arch});
      // We don't support 64-bit clang yet.
      if (!arch.equals("arm64") && !arch.equals("x86_64")) {
        data.add(new Object[] {NdkCxxPlatformCompiler.Type.CLANG, NdkCxxRuntime.GNUSTL, arch});
        data.add(new Object[] {NdkCxxPlatformCompiler.Type.CLANG, NdkCxxRuntime.LIBCXX, arch});
      }
    }
    return data;
  }

  @Parameterized.Parameter public NdkCxxPlatformCompiler.Type compiler;

  @Parameterized.Parameter(value = 1)
  public NdkCxxRuntime cxxRuntime;

  @Parameterized.Parameter(value = 2)
  public String arch;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace setupWorkspace(String name) throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, name, tmp);
    workspace.setUp();
    workspace.writeContentsToPath(
        String.format(
            "[ndk]\n"
                + "  compiler = %s\n"
                + "  gcc_version = 4.9\n"
                + "  cxx_runtime = %s\n"
                + "  cpu_abis = arm, armv7, arm64, x86, x86_64\n"
                + "  app_platform = android-21\n",
            compiler, cxxRuntime),
        ".buckconfig");
    return workspace;
  }

  private Path getNdkRoot() throws InterruptedException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(Paths.get(".").toAbsolutePath());
    DefaultAndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            projectFilesystem.getRootPath().getFileSystem(),
            ImmutableMap.copyOf(System.getenv()),
            Optional.empty(),
            Optional.empty());
    Optional<Path> ndkDir = resolver.getNdkOrAbsent();
    assertTrue(ndkDir.isPresent());
    assertTrue(java.nio.file.Files.exists(ndkDir.get()));
    return ndkDir.get();
  }

  @Before
  public void setUp() throws InterruptedException {
    AssumeAndroidPlatform.assumeNdkIsAvailable();
  }

  @Test
  public void runtimeSupportsStl() throws InterruptedException, IOException {
    assumeTrue(
        "libcxx is unsupported with this ndk",
        NdkCxxPlatforms.isSupportedConfiguration(getNdkRoot(), cxxRuntime));
    ProjectWorkspace workspace = setupWorkspace("runtime_stl");
    workspace.runBuckCommand("build", String.format("//:main#android-%s", arch)).assertSuccess();
  }

  @Test
  public void changedPlatformTarget() throws InterruptedException, IOException {
    assumeTrue(
        "libcxx is unsupported with this ndk",
        NdkCxxPlatforms.isSupportedConfiguration(getNdkRoot(), cxxRuntime));
    // 64-bit only works with platform 21, so we can't change the platform to anything else.
    assumeThat(
        "skip this test for 64-bit, for now",
        arch,
        not(anyOf(equalTo("arm64"), equalTo("x86_64"))));

    ProjectWorkspace workspace = setupWorkspace("ndk_app_platform");

    BuildTarget target = BuildTargetFactory.newInstance(String.format("//:main#android-%s", arch));
    BuildTarget linkTarget = CxxDescriptionEnhancer.createCxxLinkTarget(target, Optional.empty());

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(linkTarget.toString());

    // Change the app platform and verify that our rulekey has changed.
    workspace.writeContentsToPath("[ndk]\n  app_platform = android-12", ".buckconfig");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(linkTarget.toString());
  }

  @Test
  public void testWorkingDirectoryAndNdkHeaderPathsAreSanitized()
      throws InterruptedException, IOException {
    ProjectWorkspace workspace = setupWorkspace("ndk_debug_paths");
    workspace.writeContentsToPath(
        "[ndk]\n"
            + "  cpu_abis = arm, armv7, arm64, x86, x86_64\n"
            + "  gcc_version = 4.9\n"
            + "  app_platform = android-21\n",
        ".buckconfig");
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());
    BuildTarget target =
        BuildTargetFactory.newInstance(String.format("//:lib#android-%s,static", arch));
    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    Path lib =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, target, "%s/lib" + target.getShortName() + ".a"));
    String contents = MorePaths.asByteSource(lib).asCharSource(Charsets.ISO_8859_1).read();

    // Verify that the working directory is sanitized.
    assertFalse(contents.contains(tmp.getRoot().toString()));

    // Verify that we don't have any references to the build toolchain in the debug info.
    for (NdkCxxPlatforms.Host host : NdkCxxPlatforms.Host.values()) {
      assertFalse(contents.contains(host.toString()));
    }

    // Verify that the NDK path is sanitized.
    assertFalse(contents.contains(getNdkRoot().toString()));
  }
}
