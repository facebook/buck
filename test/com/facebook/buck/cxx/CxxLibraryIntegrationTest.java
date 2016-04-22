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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.InferHelper;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;

import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.junit.Rule;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class CxxLibraryIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void exportedPreprocessorFlagsApplyToBothTargetAndDependents() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "exported_preprocessor_flags", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:main").assertSuccess();
  }

  @Test
  public void appleBinaryBuildsOnApplePlatform() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_cxx_library", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:main#iphonesimulator-i386").assertSuccess();
  }

  @Test
  public void appleLibraryBuildsOnApplePlatform() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_cxx_library", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:lib#iphonesimulator-i386,static").assertSuccess();
  }

  @Test
  public void libraryCanIncludeAllItsHeadersAndExportedHeadersOfItsDeps() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "private_and_exported_headers", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:good-bin");
    result.assertSuccess();
  }

  @Test
  public void libraryCannotIncludePrivateHeadersOfDeps() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "private_and_exported_headers", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:bad-bin");
    result.assertFailure();
  }

  @Test
  public void libraryBuildPathIsSoName() throws IOException {
    assumeTrue(Platform.detect() == Platform.LINUX);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "shared_library", tmp);
    workspace.setUp();
    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:binary");
    assertTrue(
        Files.isRegularFile(
            workspace.getPath(
                BuildTargets.getGenPath(
                    BuildTargetFactory.newInstance("//subdir:library#default,shared"),
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
  public void runInferOnSimpleLibraryWithoutDeps() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(
        this,
        tmp,
        Optional.<String>absent());
    workspace.runBuckBuild("//foo:dep_one#infer").assertSuccess();
  }

  @Test
  public void runInferCaptureOnLibraryWithHeadersOnly() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = InferHelper.setupCxxInferWorkspace(
        this,
        tmp,
        Optional.<String>absent());
    workspace.runBuckBuild("//foo:headers_only_lib#infer-capture-all").assertSuccess();
  }

  @Test
  public void thinArchivesDoNotContainAbsolutePaths() throws IOException {
    CxxPlatform cxxPlatform =
        DefaultCxxPlatforms.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    assumeTrue(cxxPlatform.getAr().supportsThinArchives());
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_library", tmp);
    workspace.setUp();
    Path archive =
        workspace.buildAndReturnOutput(
            "-c", "cxx.archive_contents=thin",
            "//:foo#default,static");

    // NOTE: Replace the thin header with a normal header just so the commons compress parser
    // can parse the archive contents.
    try (OutputStream outputStream =
             Files.newOutputStream(workspace.getPath(archive), StandardOpenOption.WRITE)) {
      outputStream.write(ObjectFileScrubbers.GLOBAL_HEADER);
    }

    // Now iterate the archive and verify it contains no absolute paths.
    try (ArArchiveInputStream stream = new ArArchiveInputStream(
        new FileInputStream(workspace.getPath(archive).toFile()))) {
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

}
