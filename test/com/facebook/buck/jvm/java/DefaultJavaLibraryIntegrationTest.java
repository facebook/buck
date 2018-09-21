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

package com.facebook.buck.jvm.java;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.DirArtifactCacheTestUtil;
import com.facebook.buck.artifact_cache.TestArtifactCaches;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.json.HasJsonField;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.jvm.java.testutil.Bootclasspath;
import com.facebook.buck.testutil.JsonMatcher;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.ZipArchive;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TarInspector;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Integration test that verifies that a {@link DefaultJavaLibrary} writes its ABI key as part of
 * compilation.
 */
public class DefaultJavaLibraryIntegrationTest extends AbiCompilationModeTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public TemporaryPaths tmp2 = new TemporaryPaths();

  private ProjectWorkspace workspace;

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
  }

  @Test
  public void testBootclasspathIsPassedCorrectly() throws IOException {
    setUpProjectWorkspaceForScenario("bootclasspath");
    workspace.addBuckConfigLocalOption(
        "java",
        "bootclasspath-7",
        Joiner.on(":").join("boot.jar", "other.jar", Bootclasspath.getSystemBootclasspath()));
    ProcessResult processResult = workspace.runBuckBuild("-v", "5", "//:lib");
    processResult.assertSuccess();
    assertThat(
        processResult.getStderr(), allOf(containsString("boot.jar"), containsString("other.jar")));
  }

  @Test
  public void testBuildJavaLibraryWithoutSrcsAndVerifyAbi()
      throws InterruptedException, IOException, CompressorException {
    setUpProjectWorkspaceForScenario("abi");
    workspace.enableDirCache();

    // Run `buck build`.
    BuildTarget target = BuildTargetFactory.newInstance("//:no_srcs");
    ProcessResult buildResult = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    buildResult.assertSuccess("Successful build should exit with 0.");
    Path outputPath = CompilerOutputPaths.of(target, filesystem).getOutputJarPath().get();
    Path outputFile = workspace.getPath(outputPath);
    assertTrue(Files.exists(outputFile));
    // TODO(mbolin): When we produce byte-for-byte identical JAR files across builds, do:
    //
    //   HashCode hashOfOriginalJar = Files.hash(outputFile, Hashing.sha1());
    //
    // And then compare that to the output when //:no_srcs is built again with --no-cache.
    long sizeOfOriginalJar = Files.size(outputFile);

    // This verifies that the ABI key was written correctly.
    workspace.verify();

    // Verify the build cache.
    Path buildCache = workspace.getPath(filesystem.getBuckPaths().getCacheDir());
    assertTrue(Files.isDirectory(buildCache));

    ArtifactCache dirCache =
        TestArtifactCaches.createDirCacheForTest(workspace.getDestPath(), buildCache);

    int totalArtifactsCount = DirArtifactCacheTestUtil.getAllFilesInCache(dirCache).size();

    assertEquals(
        "There should be two entries (a zip and metadata) per rule key type (default and input-"
            + "based) in the build cache.",
        4,
        totalArtifactsCount);

    Sha1HashCode ruleKey = workspace.getBuildLog().getRuleKey(target.getFullyQualifiedName());

    // Run `buck clean`.
    ProcessResult cleanResult = workspace.runBuckCommand("clean", "--keep-cache");
    cleanResult.assertSuccess("Successful clean should exit with 0.");

    totalArtifactsCount = getAllFilesInPath(buildCache).size();
    assertEquals("The build cache should still exist.", 4, totalArtifactsCount);

    // Corrupt the build cache!
    Path artifactZip =
        DirArtifactCacheTestUtil.getPathForRuleKey(
            dirCache, new RuleKey(ruleKey.asHashCode()), Optional.empty());
    HashMap<String, byte[]> archiveContents = new HashMap<>(TarInspector.readTarZst(artifactZip));
    archiveContents.put(outputPath.toString(), emptyJarFile());
    writeTarZst(artifactZip, archiveContents);

    // Run `buck build` again.
    ProcessResult buildResult2 = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    buildResult2.assertSuccess("Successful build should exit with 0.");
    assertTrue(Files.isRegularFile(outputFile));

    ZipFile outputZipFile = new ZipFile(outputFile.toFile());
    assertEquals(
        "The output file will be an empty zip if it is read from the build cache.",
        0,
        outputZipFile.stream().count());
    outputZipFile.close();

    // Run `buck clean` followed by `buck build` yet again, but this time, specify `--no-cache`.
    ProcessResult cleanResult2 = workspace.runBuckCommand("clean", "--keep-cache");
    cleanResult2.assertSuccess("Successful clean should exit with 0.");
    ProcessResult buildResult3 =
        workspace.runBuckCommand("build", "--no-cache", target.getFullyQualifiedName());
    buildResult3.assertSuccess();
    outputZipFile = new ZipFile(outputFile.toFile());
    assertNotEquals(
        "The contents of the file should no longer be pulled from the corrupted build cache.",
        0,
        outputZipFile.stream().count());
    outputZipFile.close();
    assertEquals(
        "We cannot do a byte-for-byte comparision with the original JAR because timestamps might "
            + "have changed, but we verify that they are the same size, as a proxy.",
        sizeOfOriginalJar,
        Files.size(outputFile));
  }

  private byte[] emptyJarFile() throws IOException {
    ByteArrayOutputStream ostream = new ByteArrayOutputStream();
    new JarOutputStream(ostream).close();
    return ostream.toByteArray();
  }

  /**
   * writeTarZst writes a .tar.zst file to 'file'.
   *
   * <p>For each key:value in archiveContents, a file named 'key' with contents 'value' will be
   * created in the archive. File names ending with "/" are considered directories.
   */
  private void writeTarZst(Path file, Map<String, byte[]> archiveContents) throws IOException {
    try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(file));
        OutputStream z = new ZstdCompressorOutputStream(o);
        TarArchiveOutputStream archive = new TarArchiveOutputStream(z)) {
      archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      for (Entry<String, byte[]> mapEntry : archiveContents.entrySet()) {
        String fileName = mapEntry.getKey();
        byte[] fileContents = mapEntry.getValue();
        boolean isRegularFile = !fileName.endsWith("/");

        TarArchiveEntry e = new TarArchiveEntry(fileName);
        if (isRegularFile) {
          e.setSize(fileContents.length);
          archive.putArchiveEntry(e);
          archive.write(fileContents);
        } else {
          archive.putArchiveEntry(e);
        }
        archive.closeArchiveEntry();
      }
      archive.finish();
    }
  }

  @Test
  public void testBucksClasspathNotOnBuildClasspath() throws IOException {
    setUpProjectWorkspaceForScenario("guava_no_deps");

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build", "//:foo");
    buildResult.assertFailure(
        "Build should have failed since //:foo depends on Guava and "
            + "Args4j but does not include it in its deps.");

    workspace.verify();
  }

  @Test
  public void testNoDepsCompilesCleanly() throws IOException {
    setUpProjectWorkspaceForScenario("guava_no_deps");

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build", "//:bar");
    buildResult.assertSuccess("Build should have succeeded.");

    workspace.verify();
  }

  @Test
  public void testBuildJavaLibraryWithFirstOrder() throws IOException {
    setUpProjectWorkspaceForScenario("warn_on_transitive");

    // Run `buck build`.
    ProcessResult buildResult =
        workspace.runBuckCommand("build", "//:raz", "-b", "FIRST_ORDER_ONLY");
    buildResult.assertSpecialExitCode("invalid option -b", ExitCode.COMMANDLINE_ERROR);

    workspace.verify();
  }

  @Test
  public void testBuildJavaLibraryExportsDirectoryEntries() throws IOException {
    setUpProjectWorkspaceForScenario("export_directory_entries");

    // Run `buck build`.
    BuildTarget target = BuildTargetFactory.newInstance("//:empty_directory_entries");
    ProcessResult buildResult = workspace.runBuckBuild(target.getFullyQualifiedName());
    buildResult.assertSuccess();

    Path outputFile =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem, target, "lib__%s__output/" + target.getShortName() + ".jar"));
    assertTrue(Files.exists(outputFile));

    ImmutableSet.Builder<String> jarContents = ImmutableSet.builder();
    try (ZipFile zipFile = new ZipFile(outputFile.toFile())) {
      for (ZipEntry zipEntry : Collections.list(zipFile.entries())) {
        jarContents.add(zipEntry.getName());
      }
    }

    // TODO(mread): Change the output to the intended output.
    assertEquals(
        jarContents.build(),
        ImmutableSet.of("META-INF/", "META-INF/MANIFEST.MF", "swag.txt", "yolo.txt"));

    workspace.verify();
  }

  @Test
  public void testFileChangeThatDoesNotModifyAbiAvoidsRebuild() throws IOException {
    setUpProjectWorkspaceForScenario("rulekey_changed_while_abi_stable");

    // Run `buck build`.
    BuildTarget bizTarget = BuildTargetFactory.newInstance("//:biz");
    BuildTarget utilTarget = BuildTargetFactory.newInstance("//:util");
    ProcessResult buildResult =
        workspace.runBuckCommand("build", bizTarget.getFullyQualifiedName());
    buildResult.assertSuccess("Successful build should exit with 0.");

    Path utilRuleKeyPath =
        BuildTargetPaths.getScratchPath(filesystem, utilTarget, ".%s/metadata/build/RULE_KEY");
    String utilRuleKey = getContents(utilRuleKeyPath);
    Path utilAbiRuleKeyPath =
        BuildTargetPaths.getScratchPath(
            filesystem, utilTarget, ".%s/metadata/build/INPUT_BASED_RULE_KEY");
    String utilAbiRuleKey = getContents(utilAbiRuleKeyPath);

    Path bizRuleKeyPath =
        BuildTargetPaths.getScratchPath(filesystem, bizTarget, ".%s/metadata/build/RULE_KEY");
    String bizRuleKey = getContents(bizRuleKeyPath);
    Path bizAbiRuleKeyPath =
        BuildTargetPaths.getScratchPath(
            filesystem, bizTarget, ".%s/metadata/build/INPUT_BASED_RULE_KEY");
    String bizAbiRuleKey = getContents(bizAbiRuleKeyPath);

    Path utilOutputPath =
        BuildTargetPaths.getGenPath(
            filesystem, utilTarget, "lib__%s__output/" + utilTarget.getShortName() + ".jar");
    long utilJarSize = Files.size(workspace.getPath(utilOutputPath));
    Path bizOutputPath =
        BuildTargetPaths.getGenPath(
            filesystem, bizTarget, "lib__%s__output/" + bizTarget.getShortName() + ".jar");
    FileTime bizJarLastModified = Files.getLastModifiedTime(workspace.getPath(bizOutputPath));

    // TODO(mbolin): Run uber-biz.jar and verify it prints "Hello World!\n".

    // Edit Util.java in a way that does not affect its ABI.
    workspace.replaceFileContents("Util.java", "Hello World", "Hola Mundo");

    // Run `buck build` again.
    ProcessResult buildResult2 = workspace.runBuckCommand("build", "//:biz");
    buildResult2.assertSuccess("Successful build should exit with 0.");

    assertThat(utilRuleKey, not(equalTo(getContents(utilRuleKeyPath))));
    assertThat(utilAbiRuleKey, not(equalTo(getContents(utilAbiRuleKeyPath))));
    workspace.getBuildLog().assertTargetBuiltLocally(utilTarget.toString());

    assertThat(bizRuleKey, not(equalTo(getContents(bizRuleKeyPath))));
    assertEquals(bizAbiRuleKey, getContents(bizAbiRuleKeyPath));
    workspace.getBuildLog().assertTargetHadMatchingInputRuleKey(bizTarget.toString());

    assertThat(
        "util.jar should have been rewritten, so its file size should have changed.",
        utilJarSize,
        not(equalTo(Files.size(workspace.getPath(utilOutputPath)))));
    assertEquals(
        "biz.jar should not have been rewritten, so its last-modified time should be the same.",
        bizJarLastModified,
        Files.getLastModifiedTime(workspace.getPath(bizOutputPath)));

    // TODO(mbolin): Run uber-biz.jar and verify it prints "Hola Mundo!\n".

    // TODO(mbolin): This last scenario that is being tested would be better as a unit test.
    // Run `buck build` one last time. This ensures that a dependency java_library() rule (:util)
    // that is built via BuildRuleSuccess.Type.MATCHING_INPUT_BASED_RULE_KEY does not
    // explode when its dependent rule (:biz) invokes the dependency's getAbiKey() method as part of
    // its own getAbiKeyForDeps().
    ProcessResult buildResult3 = workspace.runBuckCommand("build", "//:biz");
    buildResult3.assertSuccess("Successful build should exit with 0.");
  }

  @Test
  public void testJavaLibraryOnlyDependsOnTheAbiVersionsOfItsDeps() throws IOException {
    compileAgainstAbisOnly();
    setUpProjectWorkspaceForScenario("depends_only_on_abi_test");
    workspace.enableDirCache();

    // Build A
    ProcessResult firstBuildResult = workspace.runBuckBuild("//:a");
    firstBuildResult.assertSuccess("Successful build should exit with 0.");

    // Perform clean
    ProcessResult cleanResult = workspace.runBuckCommand("clean", "--keep-cache");
    cleanResult.assertSuccess("Successful clean should exit with 0.");

    // Edit A
    workspace.replaceFileContents("A.java", "getB", "getNewB");

    // Rebuild A
    ProcessResult secondBuildResult = workspace.runBuckBuild("//:a");
    secondBuildResult.assertSuccess("Successful build should exit with 0.");

    BuildTarget b = BuildTargetFactory.newInstance("//:b");
    BuildTarget c = BuildTargetFactory.newInstance("//:c");
    BuildTarget d = BuildTargetFactory.newInstance("//:d");

    // Confirm that we got an input based rule key hit on B#abi, C#abi, D#abi
    workspace
        .getBuildLog()
        .assertTargetWasFetchedFromCache(
            b.withFlavors(JavaAbis.CLASS_ABI_FLAVOR).getFullyQualifiedName());
    workspace
        .getBuildLog()
        .assertTargetWasFetchedFromCache(
            c.withFlavors(JavaAbis.CLASS_ABI_FLAVOR).getFullyQualifiedName());
    workspace
        .getBuildLog()
        .assertTargetWasFetchedFromCache(
            d.withFlavors(JavaAbis.CLASS_ABI_FLAVOR).getFullyQualifiedName());

    // Confirm that B, C, and D were not re-built
    workspace.getBuildLog().assertNoLogEntry(b.getFullyQualifiedName());
    workspace.getBuildLog().assertNoLogEntry(c.getFullyQualifiedName());
    workspace.getBuildLog().assertNoLogEntry(d.getFullyQualifiedName());
  }

  @Test
  public void testCompileAgainstSourceOnlyAbisByDefault() throws IOException {
    compileAgainstAbisOnly();
    setUpProjectWorkspaceForScenario("depends_only_on_abi_test");

    ProcessResult result =
        workspace.runBuckBuild("--config", "java.abi_generation_mode=source_only", "//:a");
    result.assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:b#source-only-abi");
    workspace.getBuildLog().assertTargetBuiltLocally("//:c#source-only-abi");
    workspace.getBuildLog().assertTargetBuiltLocally("//:d#source-only-abi");
    workspace.getBuildLog().assertTargetIsAbsent("//:b#source-abi");
    workspace.getBuildLog().assertTargetIsAbsent("//:c#source-abi");
    workspace.getBuildLog().assertTargetIsAbsent("//:d#source-abi");
  }

  @Test
  public void testAnnotationProcessorDepChangeThatDoesNotModifyAbiCausesRebuild()
      throws IOException {
    setUpProjectWorkspaceForScenario("annotation_processors");

    // Run `buck build` to create the dep file
    BuildTarget mainTarget = BuildTargetFactory.newInstance("//:main");
    // Warm the used classes file
    ProcessResult buildResult =
        workspace.runBuckCommand("build", mainTarget.getFullyQualifiedName());
    buildResult.assertSuccess("Successful build should exit with 0.");

    workspace.getBuildLog().assertTargetBuiltLocally("//:main");
    workspace.getBuildLog().assertTargetBuiltLocally("//:util");

    // Edit a dependency of the annotation processor in a way that doesn't change the ABI
    workspace.replaceFileContents("Util.java", "false", "true");

    // Run `buck build` again.
    ProcessResult buildResult2 = workspace.runBuckCommand("build", "//:main");
    buildResult2.assertSuccess("Successful build should exit with 0.");

    // If all goes well, we'll see //:annotation_processor's dep file on disk and not rebuild it,
    // but still rebuild //:main because the code of the annotation processor has changed
    workspace.getBuildLog().assertTargetBuiltLocally("//:util");
    workspace.getBuildLog().assertTargetBuiltLocally("//:main");
    workspace.getBuildLog().assertTargetHadMatchingInputRuleKey("//:annotation_processor_lib");
  }

  @Test
  public void testAnnotationProcessorFileChangeThatDoesNotModifyAbiCausesRebuild()
      throws IOException {
    setUpProjectWorkspaceForScenario("annotation_processors");

    // Run `buck build` to create the dep file
    BuildTarget mainTarget = BuildTargetFactory.newInstance("//:main");
    // Warm the used classes file
    ProcessResult buildResult =
        workspace.runBuckCommand("build", mainTarget.getFullyQualifiedName());
    buildResult.assertSuccess("Successful build should exit with 0.");

    workspace.getBuildLog().assertTargetBuiltLocally("//:main");
    workspace.getBuildLog().assertTargetBuiltLocally("//:util");

    // Edit a source file in the annotation processor in a way that doesn't change the ABI
    workspace.replaceFileContents("AnnotationProcessor.java", "false", "true");

    // Run `buck build` again.
    ProcessResult buildResult2 = workspace.runBuckCommand("build", "//:main");
    buildResult2.assertSuccess("Successful build should exit with 0.");

    // If all goes well, we'll rebuild //:annotation_processor because of the source change,
    // and then rebuild //:main because the code of the annotation processor has changed
    workspace.getBuildLog().assertTargetHadMatchingRuleKey("//:util");
    workspace.getBuildLog().assertTargetBuiltLocally("//:main");
  }

  @Test
  public void testAnnotationProcessorFileChangeThatDoesNotModifyCodeDoesNotCauseRebuild()
      throws IOException {
    setUpProjectWorkspaceForScenario("annotation_processors");

    // Run `buck build` to create the dep file
    BuildTarget mainTarget = BuildTargetFactory.newInstance("//:main");
    // Warm the used classes file
    ProcessResult buildResult =
        workspace.runBuckCommand("build", mainTarget.getFullyQualifiedName());
    buildResult.assertSuccess("Successful build should exit with 0.");

    workspace.getBuildLog().assertTargetBuiltLocally("//:main");
    workspace.getBuildLog().assertTargetBuiltLocally("//:util");

    // Edit a source file in the annotation processor in a way that doesn't change the ABI
    workspace.replaceFileContents("AnnotationProcessor.java", "false", "false /* false */");

    // Run `buck build` again.
    ProcessResult buildResult2 = workspace.runBuckCommand("build", "//:main");
    buildResult2.assertSuccess("Successful build should exit with 0.");

    // If all goes well, we'll rebuild //:annotation_processor because of the source change,
    // and then rebuild //:main because the code of the annotation processor has changed
    workspace.getBuildLog().assertTargetHadMatchingRuleKey("//:util");
    workspace.getBuildLog().assertTargetHadMatchingInputRuleKey("//:main");
    workspace.getBuildLog().assertTargetBuiltLocally("//:annotation_processor_lib");
  }

  @Test
  public void testFileChangeThatDoesNotModifyAbiOfAUsedClassAvoidsRebuild() throws IOException {
    setUpProjectWorkspaceForScenario("dep_file_rule_key");

    // Run `buck build` to create the dep file
    BuildTarget bizTarget = BuildTargetFactory.newInstance("//:biz");
    // Warm the used classes file
    ProcessResult buildResult =
        workspace.runBuckCommand("build", bizTarget.getFullyQualifiedName());
    buildResult.assertSuccess("Successful build should exit with 0.");

    workspace.getBuildLog().assertTargetBuiltLocally("//:biz");
    workspace.getBuildLog().assertTargetBuiltLocally("//:util");

    // Edit MoreUtil.java in a way that changes its ABI
    workspace.replaceFileContents("MoreUtil.java", "printHelloWorld", "printHelloWorld2");

    // Run `buck build` again.
    ProcessResult buildResult2 = workspace.runBuckCommand("build", "//:biz");
    buildResult2.assertSuccess("Successful build should exit with 0.");

    // If all goes well, we'll fetch //:biz's dep file from the cache and realize we don't need
    // to rebuild it because //:biz didn't use MoreUtil.
    workspace.getBuildLog().assertTargetBuiltLocally("//:util");
    workspace.getBuildLog().assertTargetHadMatchingDepfileRuleKey("//:biz");
  }

  @Test
  public void testResourceFileChangeCanTakeAdvantageOfDepBasedKeys() throws IOException {
    setUpProjectWorkspaceForScenario("resource_in_dep_file");

    // Warm the used classes file
    ProcessResult buildResult = workspace.runBuckCommand("build", ":main");
    buildResult.assertSuccess("Successful build should exit with 0.");

    workspace.getBuildLog().assertTargetBuiltLocally("//:main");
    workspace.getBuildLog().assertTargetBuiltLocally("//:util");

    // Edit the unread_file.txt resource
    workspace.replaceFileContents("unread_file.txt", "hello", "goodbye");

    // Run `buck build` again.
    ProcessResult buildResult2 = workspace.runBuckCommand("build", "//:main");
    buildResult2.assertSuccess("Successful build should exit with 0.");

    // If all goes well, we'll fetch //:main's dep file from the cache and realize we don't need
    // to rebuild it because //:main didn't use unread_file.
    workspace.getBuildLog().assertTargetBuiltLocally("//:util");
    workspace.getBuildLog().assertTargetHadMatchingDepfileRuleKey("//:main");

    // Edit read_file.txt resource
    workspace.replaceFileContents("read_file.txt", "me", "you");
    workspace.runBuckCommand("build", ":main").assertSuccess();

    // Since that file was used during the compilation, we must rebuild
    workspace.getBuildLog().assertTargetBuiltLocally("//:util");
    workspace.getBuildLog().assertTargetBuiltLocally("//:main");
  }

  @Test
  public void testFileChangeThatDoesNotModifyAbiOfAUsedClassAvoidsRebuildEvenWithBuckClean()
      throws IOException {
    setUpProjectWorkspaceForScenario("dep_file_rule_key");
    workspace.enableDirCache();

    // Run `buck build` to warm the cache.
    BuildTarget bizTarget = BuildTargetFactory.newInstance("//:biz");
    // Warm the used classes file
    ProcessResult buildResult =
        workspace.runBuckCommand("build", bizTarget.getFullyQualifiedName());
    buildResult.assertSuccess("Successful build should exit with 0.");

    workspace.getBuildLog().assertTargetBuiltLocally("//:biz");
    workspace.getBuildLog().assertTargetBuiltLocally("//:util");

    // Run `buck clean` so that we're forced to fetch the dep file from the cache.
    ProcessResult cleanResult = workspace.runBuckCommand("clean", "--keep-cache");
    cleanResult.assertSuccess("Successful clean should exit with 0.");

    // Edit MoreUtil.java in a way that changes its ABI
    workspace.replaceFileContents("MoreUtil.java", "printHelloWorld", "printHelloWorld2");

    // Run `buck build` again.
    ProcessResult buildResult2 = workspace.runBuckCommand("build", "//:biz");
    buildResult2.assertSuccess("Successful build should exit with 0.");

    // If all goes well, we'll fetch //:biz's dep file from the cache and realize we don't need
    // to rebuild it because //:biz didn't use MoreUtil.
    workspace.getBuildLog().assertTargetBuiltLocally("//:util");
    workspace.getBuildLog().assertTargetWasFetchedFromCacheByManifestMatch("//:biz");
  }

  // Yes, we actually had the bug against which this test is guarding.
  @Test
  public void testAddedSourceFileInvalidatesManifest() throws IOException {
    setUpProjectWorkspaceForScenario("manifest_key");
    workspace.enableDirCache();

    // Run `buck build` to warm the cache.
    BuildTarget mainTarget = BuildTargetFactory.newInstance("//:main");
    // Warm the used classes file
    ProcessResult buildResult =
        workspace.runBuckCommand("build", mainTarget.getFullyQualifiedName());
    buildResult.assertSuccess("Successful build should exit with 0.");

    workspace.getBuildLog().assertTargetBuiltLocally("//:main");

    // Run `buck clean` so that we're forced to fetch the dep file from the cache.
    ProcessResult cleanResult = workspace.runBuckCommand("clean", "--keep-cache");
    cleanResult.assertSuccess("Successful clean should exit with 0.");

    // Add a new source file
    workspace.writeContentsToPath(
        "package com.example; public class NewClass { }", "NewClass.java");

    // Run `buck build` again.
    ProcessResult buildResult2 =
        workspace.runBuckCommand("build", mainTarget.getFullyQualifiedName());
    buildResult2.assertSuccess("Successful build should exit with 0.");

    // The new source file should result in a different manifest being downloaded and thus a
    // cache miss.
    workspace.getBuildLog().assertTargetBuiltLocally("//:main");
  }

  @Test
  public void testClassUsageFileOutput() throws IOException {
    setUpProjectWorkspaceForScenario("class_usage_file");

    // Run `buck build`.
    BuildTarget bizTarget = BuildTargetFactory.newInstance("//:biz");
    ProcessResult buildResult =
        workspace.runBuckCommand("build", bizTarget.getFullyQualifiedName());
    buildResult.assertSuccess("Successful build should exit with 0.");

    Path bizClassUsageFilePath =
        BuildTargetPaths.getGenPath(filesystem, bizTarget, "lib__%s__output/used-classes.json");

    List<String> lines = Files.readAllLines(workspace.getPath(bizClassUsageFilePath), UTF_8);

    assertEquals("Expected just one line of JSON", 1, lines.size());

    String utilJarPath;
    if (compileAgainstAbis.equals(TRUE)) {
      utilJarPath =
          MorePaths.pathWithPlatformSeparators("buck-out/gen/util#class-abi/util-abi.jar");
    } else {
      utilJarPath = MorePaths.pathWithPlatformSeparators("buck-out/gen/lib__util__output/util.jar");
    }
    String utilClassPath = MorePaths.pathWithPlatformSeparators("com/example/Util.class");

    JsonNode jsonNode = ObjectMappers.READER.readTree(lines.get(0));
    assertThat(
        jsonNode,
        new HasJsonField(
            utilJarPath,
            Matchers.equalTo(
                ObjectMappers.legacyCreate().valueToTree(new String[] {utilClassPath}))));
  }

  @Test
  public void testCanUseDepFileRuleKeysCrossCell() throws Exception {
    setUpProjectWorkspaceForScenario("class_usage_file_xcell");
    Path crossCellRoot = setUpACrossCell("away_cell", workspace.getPath("away_cell"));

    // Run `buck build`.
    BuildTarget bizTarget = BuildTargetFactory.newInstance("//:biz");
    workspace.runBuckBuild(bizTarget.getFullyQualifiedName()).assertSuccess();

    BuckBuildLog cleanBuildLog = workspace.getBuildLog();
    cleanBuildLog.assertTargetBuiltLocally("away_cell//util:util");
    cleanBuildLog.assertTargetBuiltLocally("//:biz");

    // Edit the file not used by the local target and assert we get a dep file hit
    workspace.replaceFileContents(
        crossCellRoot.resolve("util/MoreUtil.java").toString(), "// public_method", "");
    workspace.runBuckBuild(bizTarget.getFullyQualifiedName()).assertSuccess();

    BuckBuildLog depFileHitLog = workspace.getBuildLog();
    depFileHitLog.assertTargetBuiltLocally("away_cell//util:util");
    depFileHitLog.assertTargetHadMatchingDepfileRuleKey("//:biz");

    // Now edit the file not used by the local target and assert we don't get a false cache hit
    workspace.replaceFileContents(
        crossCellRoot.resolve("util/Util.java").toString(), "// public_method", "");
    workspace.runBuckBuild(bizTarget.getFullyQualifiedName()).assertSuccess();

    BuckBuildLog depFileMissLog = workspace.getBuildLog();
    depFileMissLog.assertTargetBuiltLocally("away_cell//util:util");
    depFileMissLog.assertTargetBuiltLocally("//:biz");
  }

  @Test
  public void testClassUsageFileOutputForCrossCell() throws Exception {
    setUpProjectWorkspaceForScenario("class_usage_file_xcell");
    setUpACrossCell("away_cell", workspace.getPath("away_cell"));

    // Run `buck build`.
    BuildTarget bizTarget = BuildTargetFactory.newInstance("//:biz");
    ProcessResult buildResult = workspace.runBuckBuild(bizTarget.getFullyQualifiedName());
    buildResult.assertSuccess("Successful build should exit with 0.");

    Path bizClassUsageFilePath =
        BuildTargetPaths.getGenPath(filesystem, bizTarget, "lib__%s__output/used-classes.json");

    String usedClasses = getContents(workspace.getPath(bizClassUsageFilePath));

    String utilJarPath;
    if (compileAgainstAbis.equals(TRUE)) {
      utilJarPath =
          MorePaths.pathWithPlatformSeparators(
              "/away_cell/buck-out/gen/util/util#class-abi/util-abi.jar");
    } else {
      utilJarPath =
          MorePaths.pathWithPlatformSeparators(
              "/away_cell/buck-out/gen/util/lib__util__output/util.jar");
    }
    String utilClassPath = MorePaths.pathWithPlatformSeparators("com/example/Util.class");

    JsonMatcher expectedOutputMatcher =
        new JsonMatcher(String.format("{ \"%s\": [ \"%s\" ] }", utilJarPath, utilClassPath));
    assertThat(usedClasses, expectedOutputMatcher);
  }

  @Test
  public void updatingAResourceWhichIsJavaLibraryCausesAJavaLibraryToBeRepacked()
      throws IOException {
    setUpProjectWorkspaceForScenario("resource_change_causes_repack");

    // Run `buck build`.
    ProcessResult buildResult = workspace.runBuckCommand("build", "//:lib");
    buildResult.assertSuccess("Successful build should exit with 0.");

    workspace.copyFile("ResClass.java.new", "ResClass.java");
    workspace.resetBuildLogFile();

    // The copied file changed the contents but not the ABI of :lib. Because :lib is included as a
    // resource of :res, it's expected that both :lib and :res are rebuilt (:lib because of a code
    // change, :res in order to repack the resource)
    buildResult = workspace.runBuckCommand("build", "//:lib");
    buildResult.assertSuccess("Successful build should exit with 0.");
    workspace.getBuildLog().assertTargetBuiltLocally("//:res");
    workspace.getBuildLog().assertTargetBuiltLocally("//:lib");
  }

  @Test
  public void ensureProvidedDepsAreIncludedWhenCompilingButNotWhenPackaging() throws IOException {
    setUpProjectWorkspaceForScenario("provided_deps");

    // Run `buck build`.
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//:binary");
    BuildTarget binary2Target = BuildTargetFactory.newInstance("//:binary_2");
    ProcessResult buildResult =
        workspace.runBuckCommand(
            "build", binaryTarget.getFullyQualifiedName(), binary2Target.getFullyQualifiedName());
    buildResult.assertSuccess("Successful build should exit with 0.");

    for (Path filename :
        new Path[] {
          BuildTargetPaths.getGenPath(filesystem, binaryTarget, "%s.jar"),
          BuildTargetPaths.getGenPath(filesystem, binary2Target, "%s.jar")
        }) {
      Path file = workspace.getPath(filename);
      try (ZipArchive zipArchive = new ZipArchive(file, /* for writing? */ false)) {
        Set<String> allNames = zipArchive.getFileNames();
        // Representative file from provided_deps we don't expect to be there.
        assertFalse(allNames.contains("org/junit/Test.class"));

        // Representative file from the deps that we do expect to be there.
        assertTrue(allNames.contains("com/google/common/collect/Sets.class"));

        // The file we built.
        assertTrue(allNames.contains("com/facebook/buck/example/Example.class"));
      }
    }
  }

  @Test
  public void ensureChangingDepFromProvidedToTransitiveTriggersRebuild() throws IOException {
    setUpProjectWorkspaceForScenario("provided_deps");

    workspace.runBuckBuild("//:binary").assertSuccess("Successful build should exit with 0.");

    workspace.replaceFileContents("BUCK", "provided_deps = [\":junit\"],", "");
    workspace.replaceFileContents("BUCK", "deps = [\":guava\"]", "deps = [ ':guava', ':junit' ]");
    workspace.resetBuildLogFile();

    workspace.runBuckBuild("//:binary").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:binary");
  }

  @Test
  public void ensureThatSourcePathIsSetSensibly() throws IOException {
    setUpProjectWorkspaceForScenario("sourcepath");

    ProcessResult result = workspace.runBuckBuild("//:b");

    // This should fail, since we expect the symbol for A not to be found.
    result.assertFailure();
    String stderr = result.getStderr();

    assertTrue(stderr, stderr.contains("cannot find symbol"));
  }

  @Test
  public void testSaveClassFilesToDisk() throws IOException {
    setUpProjectWorkspaceForScenario("spool_class_files_to_disk");

    BuildTarget target = BuildTargetFactory.newInstance("//:a");
    ProcessResult result = workspace.runBuckBuild(target.getFullyQualifiedName());

    result.assertSuccess();

    Path classesDir = workspace.getPath(CompilerOutputPaths.of(target, filesystem).getClassesDir());

    assertTrue(Files.exists(classesDir));
    assertTrue(Files.isDirectory(classesDir));
    ArrayList<String> classFiles = new ArrayList<>();
    for (File file : classesDir.toFile().listFiles()) {
      classFiles.add(file.getName());
    }
    assertThat(
        "There should be 2 class files saved to disk from the compiler", classFiles, hasSize(2));
    assertThat(classFiles, hasItem("A.class"));
    assertThat(classFiles, hasItem("B.class"));
  }

  @Test
  public void testSpoolClassFilesDirectlyToJar() throws IOException {
    setUpProjectWorkspaceForScenario("spool_class_files_directly_to_jar");

    BuildTarget target = BuildTargetFactory.newInstance("//:a");
    ProcessResult result = workspace.runBuckBuild(target.getFullyQualifiedName());
    result.assertSuccess();

    Path classesDir = workspace.getPath(CompilerOutputPaths.of(target, filesystem).getClassesDir());

    assertThat(Files.exists(classesDir), is(Boolean.TRUE));
    assertThat(
        "There should be no class files in disk",
        ImmutableList.copyOf(classesDir.toFile().listFiles()),
        hasSize(0));

    Path jarPath = workspace.getPath(CompilerOutputPaths.getOutputJarPath(target, filesystem));
    assertTrue(Files.exists(jarPath));
    ZipInputStream zip = new ZipInputStream(new FileInputStream(jarPath.toFile()));
    assertThat(zip.getNextEntry().getName(), is("META-INF/"));
    assertThat(zip.getNextEntry().getName(), is("META-INF/MANIFEST.MF"));
    assertThat(zip.getNextEntry().getName(), is("A.class"));
    assertThat(zip.getNextEntry().getName(), is("B.class"));
    zip.close();
  }

  @Test
  public void testCustomJavacPerTarget() throws IOException {
    setUpProjectWorkspaceForScenario("custom_javac");

    String javacTarget = "//python:javac";
    String libTarget = "//java:lib_with_custom_javac";

    BuildTarget target = BuildTargetFactory.newInstance(libTarget);
    ProcessResult result = workspace.runBuckBuild(target.getFullyQualifiedName());
    result.assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(javacTarget);
    workspace.getBuildLog().assertTargetBuiltLocally(libTarget);

    Path classesDir = workspace.getPath(CompilerOutputPaths.of(target, filesystem).getClassesDir());

    assertThat("Classes directory should exist.", Files.exists(classesDir), is(Boolean.TRUE));
    ArrayList<String> classFiles = new ArrayList<>();
    for (File file : classesDir.toFile().listFiles()) {
      classFiles.add(file.getName());
    }
    assertThat(
        "There should be 2 class files saved to disk from the compiler", classFiles, hasSize(2));
    assertThat(classFiles, hasItem("JavacMain.class"));
    assertThat(classFiles, hasItem("Extra.class"));

    Path jarPath = workspace.getPath(CompilerOutputPaths.getOutputJarPath(target, filesystem));
    assertTrue(Files.exists(jarPath));

    // Check that normal and member classes were removed as expected.
    ZipInspector zipInspector = new ZipInspector(jarPath);
    zipInspector.assertFileExists("JavacMain.class");
    zipInspector.assertFileExists("Extra.class");

    // Test that editing the custom compiler causes rebuilds correctly.
    workspace.replaceFileContents("python/javac.py", "Extra.class", "Extra2.class");
    result = workspace.runBuckBuild(target.getFullyQualifiedName());
    result.assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(javacTarget);
    workspace.getBuildLog().assertTargetBuiltLocally(libTarget);
  }

  @Test
  public void testCustomJavacInBuckConfig() throws IOException {
    setUpProjectWorkspaceForScenario("custom_javac");

    String javacTarget = "//python:javac";
    String libTarget = "//root_java:lib_with_default_javac";

    workspace.addBuckConfigLocalOption("tools", "javac", "//python:javac");

    BuildTarget target = BuildTargetFactory.newInstance(libTarget);
    ProcessResult result = workspace.runBuckBuild(target.getFullyQualifiedName());
    result.assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(javacTarget);
    workspace.getBuildLog().assertTargetBuiltLocally(libTarget);

    Path classesDir = workspace.getPath(CompilerOutputPaths.of(target, filesystem).getClassesDir());

    assertThat("Classes directory should exist.", Files.exists(classesDir), is(Boolean.TRUE));
    ArrayList<String> classFiles = new ArrayList<>();
    for (File file : classesDir.toFile().listFiles()) {
      classFiles.add(file.getName());
    }
    assertThat(
        "There should be 2 class files saved to disk from the compiler", classFiles, hasSize(2));
    assertThat(classFiles, hasItem("JavacMain.class"));
    assertThat(classFiles, hasItem("Extra.class"));

    Path jarPath = workspace.getPath(CompilerOutputPaths.getOutputJarPath(target, filesystem));
    assertTrue(Files.exists(jarPath));

    // Check that normal and member classes were removed as expected.
    ZipInspector zipInspector = new ZipInspector(jarPath);
    zipInspector.assertFileExists("JavacMain.class");
    zipInspector.assertFileExists("Extra.class");

    // Test that editing the custom compiler causes rebuilds correctly.
    workspace.replaceFileContents("python/javac.py", "Extra.class", "Extra2.class");
    result = workspace.runBuckBuild(target.getFullyQualifiedName());
    result.assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(javacTarget);
    workspace.getBuildLog().assertTargetBuiltLocally(libTarget);
  }

  @Test
  public void testSpoolClassFilesDirectlyToJarWithRemoveClasses() throws IOException {
    setUpProjectWorkspaceForScenario("spool_class_files_directly_to_jar_with_remove_classes");

    BuildTarget target = BuildTargetFactory.newInstance("//:a");
    ProcessResult result = workspace.runBuckBuild(target.getFullyQualifiedName());
    result.assertSuccess();

    Path classesDir = workspace.getPath(CompilerOutputPaths.of(target, filesystem).getClassesDir());

    assertThat("Classes directory should exist.", Files.exists(classesDir), is(Boolean.TRUE));
    assertThat(
        "No class files should be stored on disk.",
        Arrays.stream(classesDir.toFile().listFiles())
            .filter(file -> file.getName().endsWith(".class"))
            .collect(Collectors.toList()),
        hasSize(0));

    Path jarPath = workspace.getPath(CompilerOutputPaths.getOutputJarPath(target, filesystem));
    assertTrue(Files.exists(jarPath));

    // Check that normal and member classes were removed as expected.
    ZipInspector zipInspector = new ZipInspector(jarPath);
    zipInspector.assertFileExists("test/pkg/A.class");
    zipInspector.assertFileExists("test/pkg/B.class");
    zipInspector.assertFileExists("test/pkg/C.class");
    zipInspector.assertFileExists("test/pkg/RemovableZ.txt");
    zipInspector.assertFileDoesNotExist("test/pkg/RemovableZ.class");
    zipInspector.assertFileDoesNotExist("test/pkg/B$removableB.class");
    zipInspector.assertFileDoesNotExist("test/pkg/C$deletableC.class");
  }

  @Test
  public void testSaveClassFilesToDiskWithRemoveClasses() throws IOException {
    setUpProjectWorkspaceForScenario("spool_class_files_to_disk_remove_classes");

    BuildTarget target = BuildTargetFactory.newInstance("//:a");
    ProcessResult result = workspace.runBuckBuild(target.getFullyQualifiedName());
    result.assertSuccess();

    Path classesDir = workspace.getPath(CompilerOutputPaths.of(target, filesystem).getClassesDir());

    assertThat("Classes directory should exist.", Files.exists(classesDir), is(Boolean.TRUE));
    Path jarPath = workspace.getPath(CompilerOutputPaths.getOutputJarPath(target, filesystem));
    assertTrue("Jar should exist.", Files.exists(jarPath));

    // Check that normal and member classes were removed as expected.
    ZipInspector zipInspector = new ZipInspector(jarPath);
    zipInspector.assertFileExists("test/pkg/A.class");
    zipInspector.assertFileExists("test/pkg/B.class");
    zipInspector.assertFileExists("test/pkg/RemovableC.txt");
    zipInspector.assertFileDoesNotExist("test/pkg/RemovableC.class");
    zipInspector.assertFileDoesNotExist("test/pkg/B$MemberD.class");
    zipInspector.assertFileDoesNotExist("DeletableB.class");
  }

  @Test
  public void testSpoolClassFilesDirectlyToJarWithAnnotationProcessorAndRemoveClasses()
      throws IOException {
    setUpProjectWorkspaceForScenario("spool_class_files_directly_to_jar_with_annotation_processor");

    BuildTarget target = BuildTargetFactory.newInstance("//:a");
    ProcessResult result = workspace.runBuckBuild(target.getFullyQualifiedName());

    result.assertSuccess();

    Path classesDir = workspace.getPath(CompilerOutputPaths.of(target, filesystem).getClassesDir());

    assertThat(Files.exists(classesDir), is(Boolean.TRUE));
    assertThat(
        "There should be no class files in disk",
        ImmutableList.copyOf(classesDir.toFile().listFiles()),
        hasSize(0));

    Path sourcesDir =
        workspace.getPath(CompilerOutputPaths.of(target, filesystem).getAnnotationPath());

    assertThat(Files.exists(sourcesDir), is(Boolean.TRUE));
    assertThat(
        "There should be two source files in disk, from the two generated classes.",
        ImmutableList.copyOf(sourcesDir.toFile().listFiles()),
        hasSize(2));

    Path jarPath = workspace.getPath(CompilerOutputPaths.getOutputJarPath(target, filesystem));
    assertTrue(Files.exists(jarPath));

    ZipInspector zipInspector = new ZipInspector(jarPath);
    zipInspector.assertFileDoesNotExist("ImmutableC.java");
    zipInspector.assertFileExists("ImmutableC.class");
    zipInspector.assertFileDoesNotExist("RemovableD.class");
    zipInspector.assertFileExists("RemovableD");
  }

  @Test
  public void shouldIncludeUserSuppliedManifestIfProvided() throws IOException {
    setUpProjectWorkspaceForScenario("manifest");

    Manifest m = new Manifest();
    Attributes attrs = new Attributes();
    attrs.putValue("Data", "cheese");
    m.getEntries().put("Example", attrs);
    m.write(System.out);

    Path path = workspace.buildAndReturnOutput("//:library");

    try (InputStream is = Files.newInputStream(path);
        JarInputStream jis = new JarInputStream(is)) {
      Manifest manifest = jis.getManifest();
      String value = manifest.getEntries().get("Example").getValue("Data");
      assertEquals("cheese", value);
    }
  }

  @Test
  public void parseErrorsShouldBeReportedGracefully() throws IOException {
    setUpProjectWorkspaceForScenario("parse_errors");

    ProcessResult result = workspace.runBuckBuild("//:errors");
    assertThat(result.getStderr(), Matchers.stringContainsInOrder("illegal start of expression"));
  }

  @Test
  public void parseErrorsShouldBeReportedGracefullyWithAnnotationProcessors() throws IOException {
    setUpProjectWorkspaceForScenario("parse_errors_with_aps");

    ProcessResult result = workspace.runBuckBuild("//:errors");
    assertThat(result.getStderr(), Matchers.stringContainsInOrder("illegal start of expression"));
  }

  @Test
  public void parseErrorsShouldBeReportedGracefullyWithSourceOnlyAbi() throws IOException {
    setUpProjectWorkspaceForScenario("parse_errors");

    ProcessResult result =
        workspace.runBuckBuild(
            "-c", "java.abi_generation_mode=source_only", "//:errors#source-only-abi");
    assertThat(result.getStderr(), Matchers.stringContainsInOrder("illegal start of expression"));
  }

  @Test
  public void missingDepsShouldNotCrashSourceOnlyVerifier() throws IOException {
    setUpProjectWorkspaceForScenario("missing_deps");

    ProcessResult result =
        workspace.runBuckBuild("-c", "java.abi_generation_mode=source_only", "//:errors");
    result.assertFailure();
    assertThat(result.getStderr(), Matchers.not(Matchers.stringContainsInOrder("Exception")));
  }

  @Test
  public void badImportsShouldNotCrashBuck() throws IOException {
    setUpProjectWorkspaceForScenario("import_errors");

    ProcessResult result = workspace.runBuckBuild("//:errors");
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            "class file for com.example.buck.library.dep.SuperSuper not found"));
  }

  @Test
  public void annotationProcessorCrashesShouldCrashBuck() throws IOException {
    setUpProjectWorkspaceForScenario("ap_crashes");

    ProcessResult result = workspace.runBuckBuild("//:main");
    assertThat(
        result.getStderr(),
        Matchers.stringContainsInOrder(
            "Build failed:",
            "The annotation processor com.example.buck.AnnotationProcessor has crashed.",
            "java.lang.RuntimeException: java.lang.IllegalArgumentException: Test crash!   |\n|  at com.example.buck.AnnotationProcessor.process(AnnotationProcessor.java:22) |\n|  ...", // Buck frames have been stripped properly
            "Caused by: java.lang.IllegalArgumentException: Test crash!", // Without then stripping
            // out the caused
            // exception!
            "    When running <javac>.",
            "    When building rule //:main."));
  }

  @Test
  public void testExportedProvidedDepsPropagated() throws IOException {
    setUpProjectWorkspaceForScenario("exported_provided_deps");

    ProcessResult buildResult =
        workspace.runBuckCommand("build", "//:lib_with_implicit_dep_on_provided_lib");
    buildResult.assertSuccess();

    workspace.verify();
  }

  @Test
  public void testExportedProvidedDepsPropagatedThroughExportedDeps() throws IOException {
    setUpProjectWorkspaceForScenario("exported_provided_deps");

    ProcessResult buildResult =
        workspace.runBuckCommand(
            "build", "//:lib_with_implicit_dep_on_provided_lib_through_exported_deps");
    buildResult.assertSuccess();

    workspace.verify();
  }

  @Test
  public void testExportedProvidedDepsPropagatedThroughProvidedDeps() throws IOException {
    setUpProjectWorkspaceForScenario("exported_provided_deps");

    ProcessResult buildResult =
        workspace.runBuckCommand(
            "build", "//:lib_with_implicit_dep_on_provided_lib_through_provided_deps");
    buildResult.assertSuccess();

    workspace.verify();
  }

  @Test
  public void testExportedProvidedDepsPropagatedThroughExportedDepsOfAnotherLibrary()
      throws IOException {
    setUpProjectWorkspaceForScenario("exported_provided_deps");

    ProcessResult buildResult =
        workspace.runBuckCommand(
            "build", "//:lib_with_implicit_dep_on_provided_lib_through_exported_library_deps");
    buildResult.assertSuccess();

    workspace.verify();
  }

  /** Asserts that the specified file exists and returns its contents. */
  private String getContents(Path relativePathToFile) throws IOException {
    Path file = workspace.getPath(relativePathToFile);
    assertTrue(relativePathToFile + " should exist and be an ordinary file.", Files.exists(file));
    String content = Strings.nullToEmpty(new String(Files.readAllBytes(file), UTF_8)).trim();
    assertFalse(relativePathToFile + " should not be empty.", content.isEmpty());
    return content;
  }

  private ImmutableList<Path> getAllFilesInPath(Path path) throws IOException {
    List<Path> allFiles = new ArrayList<>();
    Files.walkFileTree(
        path,
        ImmutableSet.of(),
        Integer.MAX_VALUE,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            allFiles.add(file);
            return super.visitFile(file, attrs);
          }
        });
    return ImmutableList.copyOf(allFiles);
  }

  private ProjectWorkspace setUpProjectWorkspaceForScenario(String scenario) throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmp);
    workspace.setUp();
    setWorkspaceCompilationMode(workspace);
    return workspace;
  }

  private Path setUpACrossCell(String cellName, Path crossCellContents) throws IOException {
    File crossCellsRoot = tmp2.getRoot().resolve("cross_cells").toFile();
    crossCellsRoot.mkdirs();

    Path newCellLocation = crossCellsRoot.toPath().resolve(crossCellContents.getFileName());
    Files.move(crossCellContents, newCellLocation);
    workspace.addBuckConfigLocalOption("repositories", cellName, newCellLocation.toString());
    return newCellLocation;
  }
}
