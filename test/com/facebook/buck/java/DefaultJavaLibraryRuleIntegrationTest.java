/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.java;

import static java.nio.file.StandardOpenOption.WRITE;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.java.abi.AbiWriterProtocol;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;

/**
 * Integration test that verifies that a {@link DefaultJavaLibraryRule} writes its ABI key as part
 * of compilation.
 */
public class DefaultJavaLibraryRuleIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @Test
  public void testBuildJavaLibraryWithoutSrcsAndVerifyAbi() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "abi", tmp);
    workspace.setUp();

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build", "//:no_srcs");
    buildResult.assertExitCode("Successful build should exit with 0.", 0);
    File outputFile = workspace.getFile("buck-out/gen/lib__no_srcs__output/no_srcs.jar");
    assertTrue(outputFile.exists());
    // TODO(mbolin): When we produce byte-for-byte identical JAR files across builds, do:
    //
    //   HashCode hashOfOriginalJar = Files.hash(outputFile, Hashing.sha1());
    //
    // And then compare that to the output when //:no_srcs is built again with --no-cache.
    long sizeOfOriginalJar = outputFile.length();

    // This verifies that the ABI key was written correctly.
    workspace.verify();

    // Verify the build cache.
    File buildCache = workspace.getFile("cache_dir");
    assertTrue(buildCache.isDirectory());
    assertEquals("There should be one entry (a zip) in the build cache.",
        1,
        buildCache.listFiles().length);

    // Verify the ABI key entry in the build cache.
    Path artifactZip = buildCache.listFiles()[0].toPath();
    FileSystem zipFs = FileSystems.newFileSystem(artifactZip, /* loader */ null);
    Path abiKeyEntry = zipFs.getPath("/buck-out/bin/.no_srcs/metadata/ABI_KEY");
    assertEquals(AbiWriterProtocol.EMPTY_ABI_KEY,
        new String(java.nio.file.Files.readAllBytes(abiKeyEntry)));

    // Run `buck clean`.
    ProcessResult cleanResult = workspace.runBuckCommand("clean");
    cleanResult.assertExitCode("Successful clean should exit with 0.", 0);
    assertEquals("The build cache should still exist.", 1, buildCache.listFiles().length);

    // Corrupt the build cache!
    Path outputInZip = zipFs.getPath("/buck-out/gen/lib__no_srcs__output/no_srcs.jar");
    java.nio.file.Files.write(outputInZip, "Hello world!".getBytes(), WRITE);
    zipFs.close();

    // Run `buck build` again.
    ProcessResult buildResult2 = workspace.runBuckCommand("build", "//:no_srcs");
    buildResult2.assertExitCode("Successful build should exit with 0.", 0);
    assertTrue(outputFile.isFile());
    assertEquals(
        "The content of the output file will be 'Hello World!' if it is read from the build cache.",
        "Hello world!",
        Files.toString(outputFile, Charsets.UTF_8));

    // Run `buck clean` followed by `buck build` yet again, but this time, specify `--no-cache`.
    ProcessResult cleanResult2 = workspace.runBuckCommand("clean");
    cleanResult2.assertExitCode("Successful clean should exit with 0.", 0);
    ProcessResult buildResult3 = workspace.runBuckCommand("build", "--no-cache", "//:no_srcs");
    buildResult3.assertExitCode(0);
    assertNotEquals(
        "The contents of the file should no longer be pulled from the corrupted build cache.",
        "Hello world!",
        Files.toString(outputFile, Charsets.UTF_8));
    assertEquals(
        "We cannot do a byte-for-byte comparision with the original JAR because timestamps might " +
        "have changed, but we verify that they are the same size, as a proxy.",
        sizeOfOriginalJar,
        outputFile.length());
  }

  @Test
  public void testBucksClasspathNotOnBuildClasspath() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "guava_no_deps", tmp);
    workspace.setUp();

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build", "//:foo");
    buildResult.assertExitCode("Build should have failed since //:foo depends on Guava and " +
        "Args4j but does not include it in its deps.", 1);

    workspace.verify();
  }

  @Test
  public void testNoDepsCompilesCleanly() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "guava_no_deps", tmp);
    workspace.setUp();

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build", "//:bar");
    buildResult.assertExitCode("Build should have succeeded.", 0);

    workspace.verify();
  }

  @Test
  public void testBuildJavaLibraryWithTransitive() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "warn_on_transitive", tmp);
    workspace.setUp();

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build", "//:raz", "-b", "TRANSITIVE");
    buildResult.assertExitCode("Successful build should exit with 0.", 0);

    workspace.verify();
  }

  @Test
  public void testBuildJavaLibraryWithFirstOrder() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "warn_on_transitive", tmp);
    workspace.setUp();

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build",
        "//:raz",
        "-b",
        "FIRST_ORDER_ONLY");
    buildResult.assertExitCode("Build should have failed.", 1);

    workspace.verify();
  }

  @Test
  public void testBuildJavaLibraryWithWarnOnTransitive() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "warn_on_transitive", tmp);
    workspace.setUp();

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build",
        "//:raz",
        "-b",
        "WARN_ON_TRANSITIVE");

    String expectedWarning = Joiner.on("\n").join(
      "Rule //:raz builds with its transitive dependencies but not with its first order " +
          "dependencies.",
      "The following packages were missing:",
      "Blargh",
      "Meh",
      "Try adding the following deps:",
      "//:foo",
      "//:blargh");

    buildResult.assertExitCode("Build should have succeeded with warnings.", 0);

    assertThat(
        buildResult.getStderr(),
        containsString(expectedWarning));

    workspace.verify();
  }

  @Test
  public void testFileChangeThatDoesNotModifyAbiAvoidsRebuild() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "rulekey_changed_while_abi_stable", tmp);
    workspace.setUp();

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build", "//:biz");
    buildResult.assertExitCode("Successful build should exit with 0.", 0);

    String utilRuleKey = getContents("buck-out/bin/.util/metadata/RULE_KEY");
    String utilRuleKeyNoDeps = getContents("buck-out/bin/.util/metadata/RULE_KEY_NO_DEPS");
    String utilAbi = getContents("buck-out/bin/.util/metadata/ABI_KEY");
    String utilAbiForDeps = getContents("buck-out/bin/.util/metadata/ABI_KEY_FOR_DEPS");

    String bizRuleKey = getContents("buck-out/bin/.biz/metadata/RULE_KEY");
    String bizRuleKeyNoDeps = getContents("buck-out/bin/.biz/metadata/RULE_KEY_NO_DEPS");
    String bizAbi = getContents("buck-out/bin/.biz/metadata/ABI_KEY");
    String bizAbiForDeps = getContents("buck-out/bin/.biz/metadata/ABI_KEY_FOR_DEPS");

    long utilJarSize = workspace.getFile("buck-out/gen/lib__util__output/util.jar").length();
    long bizJarLastModified = workspace.getFile("buck-out/gen/lib__biz__output/biz.jar")
        .lastModified();

    // TODO(mbolin): Run uber-biz.jar and verify it prints "Hello World!\n".

    // Edit Util.java in a way that does not affect its ABI.
    String originalUtilJava = getContents("Util.java");
    String replacementContents = originalUtilJava.replace("Hello World", "Hola Mundo");
    Files.write(replacementContents, workspace.getFile("Util.java"), Charsets.UTF_8);

    // Run `buck build` again.
    ProcessResult buildResult2 = workspace.runBuckCommand("build", "//:biz");
    buildResult2.assertExitCode("Successful build should exit with 0.", 0);

    assertThat(utilRuleKey, not(equalTo(getContents("buck-out/bin/.util/metadata/RULE_KEY"))));
    assertThat(utilRuleKeyNoDeps, not(equalTo(getContents("buck-out/bin/.util/metadata/RULE_KEY_NO_DEPS"))));
    assertEquals(utilAbi, getContents("buck-out/bin/.util/metadata/ABI_KEY"));
    assertEquals(utilAbiForDeps, getContents("buck-out/bin/.util/metadata/ABI_KEY_FOR_DEPS"));

    assertThat(bizRuleKey, not(equalTo(getContents("buck-out/bin/.biz/metadata/RULE_KEY"))));
    assertEquals(bizRuleKeyNoDeps, getContents("buck-out/bin/.biz/metadata/RULE_KEY_NO_DEPS"));
    assertEquals(bizAbi, getContents("buck-out/bin/.biz/metadata/ABI_KEY"));
    assertEquals(bizAbiForDeps, getContents("buck-out/bin/.biz/metadata/ABI_KEY_FOR_DEPS"));

    assertThat(
        "util.jar should have been rewritten, so its file size should have changed.",
        utilJarSize,
        not(equalTo(workspace.getFile("buck-out/gen/lib__util__output/util.jar").length())));
    assertEquals(
        "biz.jar should not have been rewritten, so its last-modified time should be the same.",
        bizJarLastModified,
        workspace.getFile("buck-out/gen/lib__biz__output/biz.jar").lastModified());

    // TODO(mbolin): Run uber-biz.jar and verify it prints "Hola Mundo!\n".

    // TODO(mbolin): This last scenario that is being tested would be better as a unit test.
    // Run `buck build` one last time. This ensures that a dependency java_library() rule (:util)
    // that is built via BuildRuleSuccess.Type.MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS does not
    // explode when its dependent rule (:biz) invokes the dependency's getAbiKey() method as part of
    // its own getAbiKeyForDeps().
    ProcessResult buildResult3 = workspace.runBuckCommand("build", "//:biz");
    buildResult3.assertExitCode("Successful build should exit with 0.", 0);
  }

  /**
   * Asserts that the specified file exists and returns its contents.
   */
  private String getContents(String relativePathToFile) throws IOException {
    File file = workspace.getFile(relativePathToFile);
    assertTrue(relativePathToFile + " should exist and be an ordinary file.", file.exists());
    String content = Strings.nullToEmpty(Files.toString(file, Charsets.UTF_8)).trim();
    assertFalse(relativePathToFile + " should not be empty.", content.isEmpty());
    return content;
  }
}
