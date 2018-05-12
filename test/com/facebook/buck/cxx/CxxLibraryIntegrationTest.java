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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.DefaultCxxPlatforms;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.objectfile.ObjectFileScrubbers;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.InferHelper;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.junit.Rule;
import org.junit.Test;

public class CxxLibraryIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void exportedPreprocessorFlagsApplyToBothTargetAndDependents() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "exported_preprocessor_flags", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:main").assertSuccess();
  }

  @Test
  public void appleBinaryBuildsOnApplePlatform() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_cxx_library", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:main#iphonesimulator-x86_64").assertSuccess();
  }

  @Test
  public void appleLibraryBuildsOnApplePlatform() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_cxx_library", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:lib#iphonesimulator-x86_64,static").assertSuccess();
  }

  @Test
  public void libraryCanIncludeAllItsHeadersAndExportedHeadersOfItsDeps() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "private_and_exported_headers", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:good-bin");
    result.assertSuccess();
  }

  @Test
  public void libraryCannotIncludePrivateHeadersOfDeps() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "private_and_exported_headers", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:bad-bin");
    result.assertFailure();
  }

  @Test
  public void libraryBuildPathIsSoName() throws IOException {
    assumeTrue(Platform.detect() == Platform.LINUX);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "shared_library", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckBuild("//:binary");
    assertTrue(
        Files.isRegularFile(
            workspace.getPath(
                BuildTargets.getGenPath(
                    TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath()),
                    BuildTargetFactory.newInstance("//subdir:library")
                        .withFlavors(
                            DefaultCxxPlatforms.FLAVOR, CxxDescriptionEnhancer.SHARED_FLAVOR),
                    "%s/libsubdir_library.so"))));
    result.assertSuccess();
  }

  @Test
  public void forceStaticLibLinkedIntoSharedContextIsBuiltWithPic() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "force_static_pic", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:foo#shared,default").assertSuccess();
  }

  @Test
  public void preferredLinkageOverridesParentLinkStyle() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "preferred_linkage", tmp);
    workspace.setUp();
    BuckBuildLog buildLog;

    workspace.runBuckBuild("//:foo-prefer-shared#default").assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:always_static#default,static-pic");
    buildLog.assertTargetBuiltLocally("//:always_shared#default,shared");
    buildLog.assertTargetBuiltLocally("//:agnostic#default,shared");
    buildLog.assertTargetBuiltLocally("//:foo-prefer-shared#default");

    workspace.runBuckBuild("//:foo-prefer-static#default").assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:always_static#default,static");
    buildLog.assertTargetHadMatchingRuleKey("//:always_shared#default,shared");
    buildLog.assertTargetBuiltLocally("//:agnostic#default,static");
    buildLog.assertTargetBuiltLocally("//:foo-prefer-static#default");
  }

  @Test
  public void runInferOnSimpleLibraryWithoutDeps() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    workspace.runBuckBuild("//foo:dep_one#infer").assertSuccess();
  }

  @Test
  public void runInferCaptureOnLibraryWithHeadersOnly() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(this, tmp, Optional.empty());
    workspace.runBuckBuild("//foo:headers_only_lib#infer-capture-all").assertSuccess();
  }

  @Test
  public void thinArchivesDoNotContainAbsolutePaths() throws IOException {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver();
    assumeTrue(cxxPlatform.getAr().resolve(ruleResolver).supportsThinArchives());
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_library", tmp);
    workspace.setUp();
    Path archive =
        workspace.buildAndReturnOutput("-c", "cxx.archive_contents=thin", "//:foo#default,static");

    // NOTE: Replace the thin header with a normal header just so the commons compress parser
    // can parse the archive contents.
    try (OutputStream outputStream =
        Files.newOutputStream(workspace.getPath(archive), StandardOpenOption.WRITE)) {
      outputStream.write(ObjectFileScrubbers.GLOBAL_HEADER);
    }

    // Now iterate the archive and verify it contains no absolute paths.
    try (ArArchiveInputStream stream =
        new ArArchiveInputStream(new FileInputStream(workspace.getPath(archive).toFile()))) {
      ArArchiveEntry entry;
      while ((entry = stream.getNextArEntry()) != null) {
        if (!entry.getName().isEmpty()) {
          assertFalse(
              "found absolute path: " + entry.getName(),
              workspace.getDestPath().getFileSystem().getPath(entry.getName()).isAbsolute());
        }
      }
    }
  }

  @Test
  public void testCxxLibraryWithDefaultsInFlagBuildsSomething()
      throws InterruptedException, IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//foo:library_with_header");
    ProcessResult result =
        workspace.runBuckCommand(
            "build",
            target.getFullyQualifiedName(),
            "--config",
            "defaults.cxx_library.type=static-pic",
            "--config",
            "defaults.cxx_library.platform=android-armv7");
    result.assertSuccess();

    BuildTarget implicitTarget =
        target.withAppendedFlavors(
            InternalFlavor.of("static-pic"), InternalFlavor.of("android-armv7"));
    workspace.getBuildLog().assertTargetBuiltLocally(implicitTarget.getFullyQualifiedName());
  }

  @Test
  public void prebuiltLibraryWithHeaderMapDoesntChangeIncludeTypeOfOtherHeaderMaps()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "header_map_include_type", tmp);
    workspace.setUp();
    workspace.runBuckBuild("-v=3", "//:test_prebuilt").assertSuccess();
    workspace.runBuckBuild("-v=3", "//:test_lib").assertFailure();
    workspace.runBuckBuild("-v=3", "//:test_both").assertFailure();
  }

  @Test
  public void explicitHeaderOnlyDependency() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "explicit_header_only_dependency", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:binary").assertSuccess();
    ProcessResult shouldFail = workspace.runBuckBuild("//:binary-lacking-symbols").assertFailure();
    assertThat(
        "Should not link in archive of direct header-only dependency.",
        shouldFail.getStderr(),
        containsString("lib1_function"));
    assertThat(
        "Dependencies of header-only dependencies should also be header only.",
        shouldFail.getStderr(),
        containsString("lib1_dep_function"));
  }

  @Test
  public void sourceChangeInHeaderOnlyDependencyDoesntCauseRebuild() throws IOException {
    // gcc doesn't support the `-all_load` flag which we need to use to ensure our symbols don't
    // get stripped. Skip the test on linux (a gcc platform) for now.
    assumeTrue(Platform.detect() != Platform.LINUX);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "explicit_header_only_caching", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    workspace.runBuckBuild("//:binary").assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache");
    workspace.copyFile("lib1.c.new", "lib1.c");
    workspace.runBuckBuild("//:binary").assertSuccess();
    BuckBuildLog log = workspace.getBuildLog();
    log.assertTargetWasFetchedFromCache("//:lib3#default,static");
  }

  @Test
  public void sourceFromCxxGenrule() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sources_from_cxx_genrule", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:lib#default,shared").assertSuccess();
  }

  @Test
  public void headerFromCxxGenrule() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sources_from_cxx_genrule", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:lib_header#default,shared").assertSuccess();
  }

  private void assumeSymLinkTreeWithHeaderMap(Path rootPath)
      throws InterruptedException, IOException {
    // We can only disable symlink trees if header map is supported.
    HeaderMode headerMode = CxxPlatformUtils.getHeaderModeForDefaultPlatform(rootPath);
    assumeTrue(headerMode == HeaderMode.SYMLINK_TREE_WITH_HEADER_MAP);
  }

  @Test
  public void buildWithHeadersSymlink() throws InterruptedException, IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "headers_symlinks", tmp);
    workspace.setUp();
    workspace.runBuckBuild("-v=3", "//:main#default").assertSuccess();
    Path rootPath = tmp.getRoot();
    assumeSymLinkTreeWithHeaderMap(rootPath);
    assertTrue(
        Files.exists(
            rootPath.resolve(
                "buck-out/gen/foobar#header-mode-symlink-tree-with-header-map,headers/foobar/public.h")));
    assertTrue(
        Files.exists(
            rootPath.resolve("buck-out/gen/foobar#default,private-headers/foobar/private.h")));
  }

  @Test
  public void buildWithoutPublicHeadersSymlink() throws InterruptedException, IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "headers_symlinks", tmp);
    workspace.setUp();
    workspace
        .runBuckBuild(
            "-c", "cxx.exported_headers_symlinks_enabled=false", "-v=3", "//:main#default")
        .assertSuccess();
    Path rootPath = tmp.getRoot();
    assumeSymLinkTreeWithHeaderMap(rootPath);
    assertFalse(
        Files.exists(
            rootPath.resolve(
                "buck-out/gen/foobar#header-mode-symlink-tree-with-header-map,headers/foobar/public.h")));
    assertTrue(
        Files.exists(
            rootPath.resolve("buck-out/gen/foobar#default,private-headers/foobar/private.h")));
  }

  @Test
  public void buildWithoutPrivateHeadersSymlink() throws InterruptedException, IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "headers_symlinks", tmp);
    workspace.setUp();
    workspace
        .runBuckBuild("-c", "cxx.headers_symlinks_enabled=false", "-v=3", "//:main#default")
        .assertSuccess();
    Path rootPath = tmp.getRoot();
    assumeSymLinkTreeWithHeaderMap(rootPath);
    assertTrue(
        Files.exists(
            rootPath.resolve(
                "buck-out/gen/foobar#header-mode-symlink-tree-with-header-map,headers/foobar/public.h")));
    assertFalse(
        Files.exists(
            rootPath.resolve("buck-out/gen/foobar#default,private-headers/foobar/private.h")));
  }

  @Test
  public void buildWithoutHeadersSymlink() throws InterruptedException, IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "headers_symlinks", tmp);
    workspace.setUp();
    workspace
        .runBuckBuild(
            "-c",
            "cxx.headers_symlinks_enabled=false",
            "-c",
            "cxx.exported_headers_symlinks_enabled=false",
            "-v=3",
            "//:main#default")
        .assertSuccess();
    Path rootPath = tmp.getRoot();
    assumeSymLinkTreeWithHeaderMap(rootPath);
    assertFalse(
        Files.exists(
            rootPath.resolve(
                "buck-out/gen/foobar#header-mode-symlink-tree-with-header-map,headers/foobar/public.h")));
    assertFalse(
        Files.exists(
            rootPath.resolve("buck-out/gen/foobar#default,private-headers/foobar/private.h")));
  }

  @Test
  public void testExplicitReexportOfHeaderDeps() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "reexport_header_deps", tmp);
    workspace.setUp();
    // auto-reexport is off, but reexporting via exported_deps
    workspace.runBuckBuild("//:bin-explicit-reexport").assertSuccess();
    // auto-reexport is off, but not reexporting via exported_deps
    workspace.runBuckBuild("//:bin-explicit-noexport").assertFailure();
    // auto-reexport is off, no reexport, and a dependency-free exported header
    workspace.runBuckBuild("//:bin-internal-dep-noexport").assertSuccess();
    // auto-reexport is on, but not reexporting via exported_deps
    workspace.runBuckBuild("//:bin-auto-reexport").assertSuccess();
  }

  @Test
  public void locationMacroInCompilerFlags() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "compiler_flags", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:lib#default,static").assertSuccess();
  }

  @Test
  public void locationMacroInPreprocessorFlags() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "preprocessor_flags", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:lib#default,static").assertSuccess();
  }

  @Test
  public void locationMacroInExportedPreprocessorFlags() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "exported_preprocessor_flags", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:lib_with_location_macro#default,static").assertSuccess();
  }

  @Test
  public void buildWithUniqueLibraryNames() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_library", tmp);
    workspace.setUp();
    workspace
        .runBuckBuild("-c", "cxx.unique_library_name_enabled=true", "//:foo#default,static")
        .assertSuccess();
    Path rootPath = tmp.getRoot();
    assertTrue(
        Files.exists(rootPath.resolve("buck-out/gen/foo#default,static/libfoo-Z2_rLdsOWS.a")));
  }

  @Test
  public void testShouldRemapHostPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_library", tmp);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "--config", "cxx.should_remap_host_platform=false", "//:foo");
    result.assertSuccess();
    assertThat(result.getStdout(), containsString("//:foo#default"));

    String hostFlavor = CxxPlatforms.getHostFlavor().getName();
    result =
        workspace.runBuckCommand(
            "targets", "--config", "cxx.should_remap_host_platform=true", "//:foo");
    result.assertSuccess();
    assertThat(result.getStdout(), containsString("//:foo#" + hostFlavor));
  }
}
