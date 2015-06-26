/*
 * Copyright 2015-present Facebook, Inc.
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.martiansoftware.nailgun.NGContext;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

@RunWith(Parameterized.class)
public class CxxPreprocessAndCompileIntegrationTest {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return ImmutableList.of(
        new Object[] {CxxPreprocessMode.COMBINED},
        new Object[] {CxxPreprocessMode.SEPARATE},
        new Object[] {CxxPreprocessMode.PIPED});
  }

  @Parameterized.Parameter
  public CxxPreprocessMode mode;

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {

    //
    assumeTrue(
        Platform.detect() != Platform.MACOS ||
            !ImmutableSet.of(CxxPreprocessMode.SEPARATE, CxxPreprocessMode.PIPED).contains(mode));

    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "step_test", tmp);
    workspace.setUp();
    workspace.writeContentsToPath(
        "[cxx]\n" +
        "  preprocess_mode = " + mode.toString().toLowerCase() + "\n" +
        "  cppflags = -g\n" +
        "  cflags = -g\n" +
        "  cxxppflags = -g\n" +
        "  cxxflags = -g\n",
        ".buckconfig");
  }

  @Test
  public void sanitizeWorkingDirectory() throws IOException {

    // TODO(user): Currently, we don't properly sanitize the working directory for the default
    // platform when using the clang compiler.
    assumeNotUsingSeparateOrPipedModesWithClang();

    workspace.runBuckBuild("//:simple#default,static").assertSuccess();
    Path lib = workspace.getPath("buck-out/gen/simple#default,static/libsimple.a");
    String contents =
        Files.asByteSource(lib.toFile())
            .asCharSource(Charsets.ISO_8859_1)
            .read();
    assertFalse(lib.toString(), contents.contains(tmp.getRootPath().toString()));
  }

  @Test
  public void sanitizeSymlinkedWorkingDirectory() throws IOException {

    // TODO(user): Currently, we don't properly sanitize the working directory for the default
    // platform when using the clang compiler.
    assumeNotUsingSeparateOrPipedModesWithClang();

    TemporaryFolder folder = new TemporaryFolder();
    folder.create();

    // Setup up a symlink to our working directory.
    Path symlinkedRoot = folder.getRoot().toPath().resolve("symlinked-root");
    java.nio.file.Files.createSymbolicLink(symlinkedRoot, tmp.getRootPath());

    // Run the build, setting PWD to the above symlink.  Typically, this causes compilers to use
    // the symlinked directory, even though it's not the right project root.
    Map<String, String> envCopy = Maps.newHashMap(System.getenv());
    envCopy.put("PWD", symlinkedRoot.toString());
    workspace.runBuckCommandWithEnvironmentAndContext(
        tmp.getRootPath(),
        Optional.<NGContext>absent(),
        Optional.<BuckEventListener>absent(),
        Optional.of(ImmutableMap.copyOf(envCopy)),
        "build",
        "//:simple#default,static")
            .assertSuccess();

    // Verify that we still sanitized this path correctly.
    Path lib = workspace.getPath("buck-out/gen/simple#default,static/libsimple.a");
    String contents =
        Files.asByteSource(lib.toFile())
            .asCharSource(Charsets.ISO_8859_1)
            .read();
    assertFalse(lib.toString(), contents.contains(tmp.getRootPath().toString()));
    assertFalse(lib.toString(), contents.contains(symlinkedRoot.toString()));

    folder.delete();
  }

  public void assumeNotUsingSeparateOrPipedModesWithClang() {
    assumeTrue(
        Platform.detect() != Platform.MACOS ||
        !ImmutableSet.of(CxxPreprocessMode.SEPARATE, CxxPreprocessMode.PIPED).contains(mode));
  }

}
