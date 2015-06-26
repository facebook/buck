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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cxx.CxxPreprocessMode;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultPropertyFinder;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;

@RunWith(Parameterized.class)
public class NdkCxxPlatformIntegrationTest {

  @Parameterized.Parameters(name = "{0},{1},{2},{3}")
  public static Collection<Object[]> data() {
    List<Object[]> data = Lists.newArrayList();
    for (String arch : ImmutableList.of("arm", "armv7", "x86")) {
      for (CxxPreprocessMode mode : CxxPreprocessMode.values()) {
        data.add(
            new Object[]{
                NdkCxxPlatforms.Compiler.Type.GCC,
                NdkCxxPlatforms.CxxRuntime.GNUSTL,
                arch,
                mode});
        data.add(
            new Object[]{
                NdkCxxPlatforms.Compiler.Type.CLANG,
                NdkCxxPlatforms.CxxRuntime.GNUSTL,
                arch,
                mode});
        data.add(
            new Object[]{
                NdkCxxPlatforms.Compiler.Type.CLANG,
                NdkCxxPlatforms.CxxRuntime.LIBCXX,
                arch,
                mode});
      }
    }
    return data;
  }

  @Parameterized.Parameter
  public NdkCxxPlatforms.Compiler.Type compiler;

  @Parameterized.Parameter(value = 1)
  public NdkCxxPlatforms.CxxRuntime cxxRuntime;

  @Parameterized.Parameter(value = 2)
  public String arch;

  @Parameterized.Parameter(value = 3)
  public CxxPreprocessMode mode;

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private ProjectWorkspace setupWorkspace(String name) throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, name, tmp);
    workspace.setUp();
    workspace.writeContentsToPath(
        String.format(
            "[cxx]\n  preprocess_mode = %s\n" +
            "[ndk]\n  compiler = %s\n  cxx_runtime = %s\n",
            mode.toString().toLowerCase(),
            compiler,
            cxxRuntime),
        ".buckconfig");
    return workspace;
  }

  private Path getNdkRoot() {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(Paths.get("."));
    DefaultAndroidDirectoryResolver resolver = new DefaultAndroidDirectoryResolver(
        projectFilesystem,
        Optional.<String>absent(),
        new DefaultPropertyFinder(projectFilesystem, ImmutableMap.copyOf(System.getenv())));
    Optional<Path> ndkDir = resolver.findAndroidNdkDir();
    assertTrue(ndkDir.isPresent());
    assertTrue(java.nio.file.Files.exists(ndkDir.get()));
    return ndkDir.get();
  }

  @Before
  public void setUp() {
    AssumeAndroidPlatform.assumeNdkIsAvailable();
  }

  @Test
  public void runtimeSupportsStl() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("runtime_stl");
    workspace.runBuckCommand("build", String.format("//:main#android-%s", arch)).assertSuccess();
  }

  @Test
  public void changedPlatformTarget() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("ndk_app_platform");

    String target = String.format("//:main#android-%s", arch);

    workspace.runBuckCommand("build", target).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(target);

    // Change the app platform and verify that our rulekey has changed.
    workspace.writeContentsToPath("[ndk]\n  app_platform = android-12", ".buckconfig");
    workspace.runBuckCommand("build", target).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(target);
  }

  @Test
  public void testWorkingDirectoryAndNdkHeaderPathsAreSanitized() throws IOException {
    ProjectWorkspace workspace = setupWorkspace("ndk_debug_paths");
    workspace.runBuckBuild(String.format("//:lib#android-%s,static", arch)).assertSuccess();
    java.io.File lib =
        workspace.getFile(String.format("buck-out/gen/lib#android-%s,static/liblib.a", arch));
    String contents =
        Files.asByteSource(lib)
            .asCharSource(Charsets.ISO_8859_1)
            .read();

    // Verify that the working directory is sanitized.
    assertFalse(contents.contains(tmp.getRootPath().toString()));

    // TODO(user): We don't currently support fixing up debug paths for the combined flow.
    if (mode != CxxPreprocessMode.COMBINED) {

      // Verify that we don't have any references to the build toolchain in the debug info.
      for (NdkCxxPlatforms.Host host : NdkCxxPlatforms.Host.values()) {
        assertFalse(contents.contains(host.toString()));
      }

      // Verify that the NDK path is sanitized.
      assertFalse(contents.contains(getNdkRoot().toString()));
    }

  }

}
