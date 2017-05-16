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

package com.facebook.buck.android;

import static com.facebook.buck.android.AndroidNdkHelper.SymbolGetter;
import static com.facebook.buck.android.AndroidNdkHelper.SymbolsAndDtNeeded;
import static com.facebook.buck.testutil.RegexMatcher.containsRegex;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.relinker.Symbols;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultOnDiskBuildInfo;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FilesystemBuildInfoStore;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.zip.ZipConstants;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.hamcrest.Matchers;
import org.hamcrest.collection.IsIn;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidBinaryIntegrationTest extends AbiCompilationModeTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  private ProjectFilesystem filesystem;

  private static final String SIMPLE_TARGET = "//apps/multidex:app";
  private static final String RAW_DEX_TARGET = "//apps/multidex:app-art";
  private static final String APP_REDEX_TARGET = "//apps/sample:app_redex";

  @Before
  public void setUp() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            new AndroidBinaryIntegrationTest(), "android_project", tmpFolder);
    workspace.setUp();
    setWorkspaceCompilationMode(workspace);
    filesystem = new ProjectFilesystem(workspace.getDestPath());
  }

  @Test
  public void testNonExopackageHasSecondary() throws IOException {
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(SIMPLE_TARGET), "%s.apk")));

    zipInspector.assertFileExists("assets/secondary-program-dex-jars/metadata.txt");
    zipInspector.assertFileExists("assets/secondary-program-dex-jars/secondary-1.dex.jar");
    zipInspector.assertFileDoesNotExist("classes2.dex");

    zipInspector.assertFileExists("classes.dex");
    zipInspector.assertFileExists("lib/armeabi/libnative_cxx_lib.so");
  }

  @Test
  public void testAppHasAssets() throws IOException {
    Path apkPath = workspace.buildAndReturnOutput(SIMPLE_TARGET);

    ZipInspector zipInspector = new ZipInspector(workspace.getPath(apkPath));
    zipInspector.assertFileExists("assets/asset_file.txt");
    zipInspector.assertFileExists("assets/hilarity.txt");
    zipInspector.assertFileContents(
        "assets/hilarity.txt",
        workspace.getFileContents("res/com/sample/base/buck-assets/hilarity.txt"));

    // Test that after changing an asset, the new asset is in the apk.
    String newContents = "some new contents";
    workspace.writeContentsToPath(newContents, "res/com/sample/base/buck-assets/hilarity.txt");
    workspace.buildAndReturnOutput(SIMPLE_TARGET);
    zipInspector = new ZipInspector(workspace.getPath(apkPath));
    zipInspector.assertFileContents("assets/hilarity.txt", newContents);
  }

  @Test
  public void testAppAssetsAreCompressed() throws IOException {
    // Small files don't get compressed. Make something a bit bigger.
    String largeContents =
        Joiner.on("\n").join(Collections.nCopies(100, "A boring line of content."));
    workspace.writeContentsToPath(largeContents, "res/com/sample/base/buck-assets/hilarity.txt");

    Path apkPath = workspace.buildAndReturnOutput(SIMPLE_TARGET);
    ZipInspector zipInspector = new ZipInspector(workspace.getPath(apkPath));
    zipInspector.assertFileExists("assets/asset_file.txt");
    zipInspector.assertFileExists("assets/hilarity.txt");
    zipInspector.assertFileContents(
        "assets/hilarity.txt",
        workspace.getFileContents("res/com/sample/base/buck-assets/hilarity.txt"));
    zipInspector.assertFileIsCompressed("assets/hilarity.txt");
  }

  @Test
  public void testAppUncompressableAssetsAreNotCompressed() throws IOException {
    // Small files don't get compressed. Make something a bit bigger.
    String largeContents =
        Joiner.on("\n").join(Collections.nCopies(100, "A boring line of content."));
    workspace.writeContentsToPath(largeContents, "res/com/sample/base/buck-assets/movie.mp4");

    Path apkPath = workspace.buildAndReturnOutput(SIMPLE_TARGET);
    ZipInspector zipInspector = new ZipInspector(workspace.getPath(apkPath));
    zipInspector.assertFileExists("assets/asset_file.txt");
    zipInspector.assertFileExists("assets/movie.mp4");
    zipInspector.assertFileContents(
        "assets/movie.mp4", workspace.getFileContents("res/com/sample/base/buck-assets/movie.mp4"));
    zipInspector.assertFileIsNotCompressed("assets/movie.mp4");
  }

  @Test
  public void testGzAssetsAreRejected() throws IOException {
    workspace.writeContentsToPath("some contents", "res/com/sample/base/buck-assets/zipped.gz");

    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild(SIMPLE_TARGET);
    result.assertFailure();
    assertTrue(result.getStderr().contains("zipped.gz"));
  }

  @Test
  public void testRawSplitDexHasSecondary() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", RAW_DEX_TARGET);
    result.assertSuccess();

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(RAW_DEX_TARGET), "%s.apk")));
    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/metadata.txt");

    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/secondary-1.dex.jar");
    zipInspector.assertFileExists("classes2.dex");

    zipInspector.assertFileExists("classes.dex");
    zipInspector.assertFileExists("lib/armeabi/libnative_cxx_lib.so");
  }

  @Test
  public void testDisguisedExecutableIsRenamed() throws IOException {
    Path output = workspace.buildAndReturnOutput("//apps/sample:app_with_disguised_exe");
    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("lib/armeabi/libmybinary.so");
  }

  @Test
  public void testNdkLibraryIsIncluded() throws IOException {
    Path output = workspace.buildAndReturnOutput("//apps/sample:app_with_ndk_library");
    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("lib/armeabi/libfakenative.so");
  }

  @Test
  public void testEditingNdkLibraryForcesRebuild() throws IOException, InterruptedException {
    String apkWithNdkLibrary = "//apps/sample:app_with_ndk_library";
    Path output = workspace.buildAndReturnOutput(apkWithNdkLibrary);
    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("lib/armeabi/libfakenative.so");

    // Sleep 1 second (plus another half to be super duper safe) to make sure that
    // fakesystem.c gets a later timestamp than the fakesystem.o that was produced
    // during the build in setUp.  If we don't do this, there's a chance that the
    // ndk-build we run during the upcoming build will not rebuild it (on filesystems
    // that have 1-second granularity for last modified).
    // To verify this, create a Makefile with the following rule (don't forget to use a tab):
    // out: in
    //   cat $< > $@
    // Run: echo foo > in ; make ; cat out ; echo bar > in ; make ; cat out
    // On a filesystem with 1-second mtime granularity, the last "cat" should print "foo"
    // (with very high probability).
    Thread.sleep(1500);
    workspace.replaceFileContents(
        "native/fakenative/jni/fakesystem.c", "exit(status)", "exit(1+status)");

    workspace.resetBuildLogFile();
    workspace.buildAndReturnOutput(apkWithNdkLibrary);
    workspace.getBuildLog().assertTargetBuiltLocally(apkWithNdkLibrary);
  }

  @Test
  public void testEditingPrimaryDexClassForcesRebuildForSimplePackage() throws IOException {
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();

    workspace.replaceFileContents(
        "java/com/sample/app/MyApplication.java", "package com", "package\ncom");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", SIMPLE_TARGET);
    result.assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(SIMPLE_TARGET);
  }

  @Test
  public void testEditingSecondaryDexClassForcesRebuildForSimplePackage() throws IOException {
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();

    workspace.replaceFileContents("java/com/sample/lib/Sample.java", "package com", "package\ncom");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", SIMPLE_TARGET);
    result.assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(SIMPLE_TARGET);
  }

  @Test
  public void testNotAllJavaLibrariesFetched() throws IOException {
    String target = "//apps/multidex:app_with_deeper_deps";
    workspace.runBuckCommand("build", target).assertSuccess();
    workspace.replaceFileContents(
        "java/com/sample/app/MyApplication.java", "package com", "package\ncom");

    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", target).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetIsAbsent("//java/com/sample/lib:lib");
  }

  @Test
  public void testPreprocessorForcesReDex() throws IOException {
    String target = "//java/com/preprocess:disassemble";
    Path outputFile = workspace.buildAndReturnOutput(target);
    String output = new String(Files.readAllBytes(outputFile), UTF_8);
    assertThat(output, containsString("content=2"));

    workspace.replaceFileContents("java/com/preprocess/convert.py", "content=2", "content=3");

    outputFile = workspace.buildAndReturnOutput(target);
    output = new String(Files.readAllBytes(outputFile), UTF_8);
    assertThat(output, containsString("content=3"));
  }

  @Test
  public void testDxFindsReferencedResources() throws InterruptedException, IOException {
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();

    ProjectFilesystem filesystem = new ProjectFilesystem(tmpFolder.getRoot());
    DefaultOnDiskBuildInfo buildInfo =
        new DefaultOnDiskBuildInfo(
            BuildTargetFactory.newInstance("//java/com/sample/lib:lib#dex"),
            filesystem,
            new FilesystemBuildInfoStore(filesystem));
    Optional<ImmutableList<String>> resourcesFromMetadata =
        buildInfo.getValues(DexProducedFromJavaLibrary.REFERENCED_RESOURCES);
    assertTrue(resourcesFromMetadata.isPresent());
    assertEquals(
        ImmutableSet.of("com.sample.top_layout", "com.sample2.title"),
        ImmutableSet.copyOf(resourcesFromMetadata.get()));
  }

  @Test
  public void testDexingIsInputBased() throws IOException {
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//java/com/sample/lib:lib#dex");

    workspace.replaceFileContents(
        "java/com/sample/lib/Sample.java", "import", "import /* no output change */");
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertNotTargetBuiltLocally("//java/com/sample/lib:lib#dex");
    buildLog.assertTargetHadMatchingInputRuleKey("//java/com/sample/lib:lib#dex");

    workspace.replaceFileContents(
        "java/com/sample/lib/Sample.java", "import", "import /* \n some output change */");
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//java/com/sample/lib:lib#dex");
  }

  @Test
  public void testCxxLibraryDep() throws IOException {
    String target = "//apps/sample:app_cxx_lib_dep";
    workspace.runBuckCommand("build", target).assertSuccess();

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("lib/armeabi/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/armeabi/libgnustl_shared.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libgnustl_shared.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/x86/libgnustl_shared.so");
  }

  @Test
  public void testCxxLibraryDepModular() throws IOException {
    String target = "//apps/sample:app_cxx_lib_dep_modular";
    workspace.runBuckCommand("build", target).assertSuccess();

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileDoesNotExist("lib/armeabi/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/armeabi/libgnustl_shared.so");
    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libgnustl_shared.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/x86/libgnustl_shared.so");
    zipInspector.assertFileExists("assets/native.cxx.lib/libs.txt");
    zipInspector.assertFileExists("assets/native.cxx.lib/libs.xzs");
  }

  @Test
  public void testCxxLibraryDepClang() throws IOException {
    String target = "//apps/sample:app_cxx_lib_dep";
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand(
            "build", "-c", "ndk.compiler=clang", "-c", "ndk.cxx_runtime=libcxx", target);
    result.assertSuccess();

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("lib/armeabi/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/armeabi/libc++_shared.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libc++_shared.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/x86/libc++_shared.so");
  }

  @Test
  public void testCxxLibraryDepWithNoFilters() throws IOException {
    String target = "//apps/sample:app_cxx_lib_dep_no_filters";
    workspace.runBuckCommand("build", target).assertSuccess();

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("lib/armeabi/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_lib.so");
  }

  @Test
  public void testNoCxxDepsDoesNotIncludeNdkRuntime() throws IOException {
    String target = "//apps/sample:app_no_cxx_deps";
    workspace.runBuckCommand("build", target).assertSuccess();

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileDoesNotExist("lib/armeabi/libgnustl_shared.so");
    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libgnustl_shared.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libgnustl_shared.so");
  }

  @Test
  public void testProguardDontObfuscateGeneratesMappingFile() throws IOException {
    String target = "//apps/sample:app_proguard_dontobfuscate";
    workspace.runBuckCommand("build", target).assertSuccess();

    Path mapping =
        workspace.getPath(
            BuildTargets.getGenPath(
                filesystem, BuildTargetFactory.newInstance(target), "%s/proguard/mapping.txt"));
    assertTrue(Files.exists(mapping));
  }

  @Test
  public void testStaticCxxLibraryDep() throws IOException {
    String target = "//apps/sample:app_static_cxx_lib_dep";
    workspace.runBuckCommand("build", target).assertSuccess();

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_bar.so");
  }

  private static Path unzip(Path tmpDir, Path zipPath, String name) throws IOException {
    Path outPath = tmpDir.resolve(zipPath.getFileName());
    try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
      Files.copy(
          zipFile.getInputStream(zipFile.getEntry(name)),
          outPath,
          StandardCopyOption.REPLACE_EXISTING);
      return outPath;
    }
  }

  @Test
  public void testNativeLibraryMerging() throws IOException, InterruptedException {
    NdkCxxPlatform platform = AndroidNdkHelper.getNdkCxxPlatform(workspace, filesystem);
    SourcePathResolver pathResolver =
        new SourcePathResolver(
            new SourcePathRuleFinder(
                new BuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));
    Path tmpDir = tmpFolder.newFolder("merging_tmp");
    SymbolGetter syms =
        new SymbolGetter(
            new DefaultProcessExecutor(new TestConsole()),
            tmpDir,
            platform.getObjdump(),
            pathResolver);
    SymbolsAndDtNeeded info;

    workspace.replaceFileContents(".buckconfig", "#cpu_abis", "cpu_abis = x86");
    ImmutableMap<String, Path> paths =
        workspace.buildMultipleAndReturnOutputs(
            "//apps/sample:app_with_merged_libs",
            "//apps/sample:app_with_alternate_merge_glue",
            "//apps/sample:app_with_alternate_merge_glue_and_localized_symbols",
            "//apps/sample:app_with_merged_libs_modular");

    Path apkPath = paths.get("//apps/sample:app_with_merged_libs");
    ZipInspector zipInspector = new ZipInspector(apkPath);
    zipInspector.assertFileDoesNotExist("lib/x86/lib1a.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib1b.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib2e.so");
    zipInspector.assertFileDoesNotExist("lib/x86/lib2f.so");

    info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/lib1.so");
    assertThat(info.symbols.global, Matchers.hasItem("A"));
    assertThat(info.symbols.global, Matchers.hasItem("B"));
    assertThat(info.symbols.global, Matchers.hasItem("glue_1"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, Matchers.hasItem("libnative_merge_C.so"));
    assertThat(info.dtNeeded, Matchers.hasItem("libnative_merge_D.so"));
    assertThat(info.dtNeeded, not(Matchers.hasItem("libnative_merge_B.so")));

    info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/libnative_merge_C.so");
    assertThat(info.symbols.global, Matchers.hasItem("C"));
    assertThat(info.symbols.global, Matchers.hasItem("static_func_C"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_1")));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, Matchers.hasItem("libnative_merge_D.so"));
    assertThat(info.dtNeeded, Matchers.hasItem("libprebuilt_for_C.so"));

    info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/libnative_merge_D.so");
    assertThat(info.symbols.global, Matchers.hasItem("D"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_1")));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, Matchers.hasItem("lib2.so"));
    assertThat(info.dtNeeded, not(Matchers.hasItem("libnative_merge_E.so")));
    assertThat(info.dtNeeded, not(Matchers.hasItem("libnative_merge_F.so")));

    info = syms.getSymbolsAndDtNeeded(apkPath, "lib/x86/lib2.so");
    assertThat(info.symbols.global, Matchers.hasItem("E"));
    assertThat(info.symbols.global, Matchers.hasItem("F"));
    assertThat(info.symbols.global, Matchers.hasItem("static_func_F"));
    assertThat(info.symbols.global, Matchers.hasItem("glue_1"));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));
    assertThat(info.dtNeeded, Matchers.hasItem("libprebuilt_for_F.so"));

    Path otherPath = paths.get("//apps/sample:app_with_alternate_merge_glue");
    info = syms.getSymbolsAndDtNeeded(otherPath, "lib/x86/lib2.so");
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_1")));
    assertThat(info.symbols.global, Matchers.hasItem("glue_2"));
    assertThat(info.dtNeeded, Matchers.hasItem("libprebuilt_for_F.so"));

    Path localizePath =
        paths.get("//apps/sample:app_with_alternate_merge_glue_and_localized_symbols");
    info = syms.getSymbolsAndDtNeeded(localizePath, "lib/x86/lib2.so");
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_1")));
    assertThat(info.symbols.global, not(Matchers.hasItem("glue_2")));

    Path modularPath = paths.get("//apps/sample:app_with_merged_libs_modular");
    ZipInspector modularZipInspector = new ZipInspector(modularPath);
    modularZipInspector.assertFileDoesNotExist("lib/x86/lib1a.so");
    modularZipInspector.assertFileDoesNotExist("lib/x86/lib1b.so");
    modularZipInspector.assertFileDoesNotExist("lib/x86/lib2e.so");
    modularZipInspector.assertFileDoesNotExist("lib/x86/lib2f.so");
    modularZipInspector.assertFileExists("assets/native.merge.A/libs.txt");
    modularZipInspector.assertFileExists("assets/native.merge.A/libs.xzs");
    modularZipInspector.assertFileDoesNotExist("lib/x86/lib1.so");
    modularZipInspector.assertFileDoesNotExist("lib/x86/lib2.so");

    Path disassembly =
        workspace.buildAndReturnOutput("//apps/sample:disassemble_app_with_merged_libs_gencode");
    List<String> disassembledLines = filesystem.readLines(disassembly);

    Pattern fieldPattern =
        Pattern.compile("^\\.field public static final ([^:]+):Ljava/lang/String; = \"([^\"]+)\"$");
    ImmutableMap.Builder<String, String> mapBuilder = ImmutableMap.builder();
    for (String line : disassembledLines) {
      Matcher m = fieldPattern.matcher(line);
      if (!m.matches()) {
        continue;
      }
      mapBuilder.put(m.group(1), m.group(2));
    }

    assertThat(
        mapBuilder.build(),
        Matchers.equalTo(
            ImmutableMap.of(
                "lib1a_so", "lib1_so",
                "lib1b_so", "lib1_so",
                "lib2e_so", "lib2_so",
                "lib2f_so", "lib2_so")));
  }

  @Test
  public void testNativeLibraryMergeErrors() throws IOException, InterruptedException {
    try {
      workspace.runBuckBuild("//apps/sample:app_with_merge_lib_into_two_targets");
      Assert.fail("No exception from trying to merge lib into two targets.");
    } catch (RuntimeException e) {
      assertThat(e.getMessage(), Matchers.containsString("into both"));
    }

    try {
      workspace.runBuckBuild("//apps/sample:app_with_cross_asset_merged_libs");
      Assert.fail("No exception from trying to merge between asset and non-asset.");
    } catch (RuntimeException e) {
      assertThat(e.getMessage(), Matchers.containsString("contains both asset and non-asset"));
    }

    // An older version of the code made this illegal.
    // Keep the test around in case we want to restore this behavior.
    //    try {
    //      workspace.runBuckBuild("//apps/sample:app_with_merge_into_existing_lib");
    //      Assert.fail("No exception from trying to merge into existing name.");
    //    } catch (RuntimeException e) {
    //      assertThat(e.getMessage(), Matchers.containsString("already a library name"));
    //    }

    try {
      workspace.runBuckBuild("//apps/sample:app_with_circular_merged_libs");
      Assert.fail("No exception from trying circular merged dep.");
    } catch (RuntimeException e) {
      assertThat(e.getMessage(), Matchers.containsString("Dependency cycle"));
    }

    try {
      workspace.runBuckBuild("//apps/sample:app_with_circular_merged_libs_including_root");
      Assert.fail("No exception from trying circular merged dep.");
    } catch (RuntimeException e) {
      assertThat(e.getMessage(), Matchers.containsString("Dependency cycle"));
    }

    try {
      workspace.runBuckBuild("//apps/sample:app_with_invalid_native_lib_merge_glue");
      Assert.fail("No exception from trying invalid glue.");
    } catch (RuntimeException e) {
      assertThat(e.getMessage(), Matchers.matchesPattern(".*glue.*is not linkable.*"));
    }
  }

  @Test
  public void testNativeRelinker() throws IOException, InterruptedException {
    NdkCxxPlatform platform = AndroidNdkHelper.getNdkCxxPlatform(workspace, filesystem);
    SourcePathResolver pathResolver =
        new SourcePathResolver(
            new SourcePathRuleFinder(
                new BuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));
    Path tmpDir = tmpFolder.newFolder("xdso");
    SymbolGetter syms =
        new SymbolGetter(
            new DefaultProcessExecutor(new TestConsole()),
            tmpDir,
            platform.getObjdump(),
            pathResolver);
    Symbols sym;

    Path apkPath = workspace.buildAndReturnOutput("//apps/sample:app_xdso_dce");

    sym = syms.getSymbols(apkPath, "lib/x86/libnative_xdsodce_top.so");
    assertTrue(sym.global.contains("_Z10JNI_OnLoadii"));
    assertTrue(sym.undefined.contains("_Z10midFromTopi"));
    assertTrue(sym.undefined.contains("_Z10botFromTopi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    sym = syms.getSymbols(apkPath, "lib/x86/libnative_xdsodce_mid.so");
    assertTrue(sym.global.contains("_Z10midFromTopi"));
    assertTrue(sym.undefined.contains("_Z10botFromMidi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    sym = syms.getSymbols(apkPath, "lib/x86/libnative_xdsodce_bot.so");
    assertTrue(sym.global.contains("_Z10botFromTopi"));
    assertTrue(sym.global.contains("_Z10botFromMidi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    // Run some verification on the same apk with native_relinker disabled.
    apkPath = workspace.buildAndReturnOutput("//apps/sample:app_no_xdso_dce");

    sym = syms.getSymbols(apkPath, "lib/x86/libnative_xdsodce_top.so");
    assertTrue(sym.all.contains("_Z6unusedi"));

    sym = syms.getSymbols(apkPath, "lib/x86/libnative_xdsodce_mid.so");
    assertTrue(sym.all.contains("_Z6unusedi"));

    sym = syms.getSymbols(apkPath, "lib/x86/libnative_xdsodce_bot.so");
    assertTrue(sym.all.contains("_Z6unusedi"));
  }

  @Test
  public void testHeaderOnlyCxxLibrary() throws IOException {
    String target = "//apps/sample:app_header_only_cxx_lib_dep";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_headeronly.so");
  }

  @Test
  public void testX86OnlyCxxLibrary() throws IOException {
    String target = "//apps/sample:app_with_x86_lib";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libnative_cxx_x86-only.so");
    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libgnustl_shared.so");
    zipInspector.assertFileDoesNotExist("lib/armeabi/libnative_cxx_x86-only.so");
    zipInspector.assertFileDoesNotExist("lib/armeabi/libgnustl_shared.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_x86-only.so");
    zipInspector.assertFileExists("lib/x86/libgnustl_shared.so");
  }

  @Test
  public void testApksHaveDeterministicTimestamps() throws IOException {
    String target = "//apps/sample:app";
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertSuccess();

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Path apk =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is = new ZipInputStream(Files.newInputStream(apk))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertThat(entry.getName(), new Date(entry.getTime()), Matchers.equalTo(dosEpoch));
      }
    }
  }

  @Test
  public void testCxxLibraryAsAsset() throws IOException {
    String target = "//apps/sample:app_cxx_lib_asset";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo2.so");
  }

  @Test
  public void testCxxLibraryAsAssetWithoutPackaging() throws IOException {
    String target = "//apps/sample:app_cxx_lib_asset_no_package";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_libasset.so");
  }

  @Test
  public void testCompressAssetLibs() throws IOException {
    String target = "//apps/sample:app_compress_lib_asset";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("assets/lib/libs.xzs");
    zipInspector.assertFileExists("assets/lib/metadata.txt");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo2.so");
  }

  @Test
  public void testCompressAssetLibsModular() throws IOException {
    String target = "//apps/sample:app_compress_lib_asset_modular";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("assets/lib/libs.xzs");
    zipInspector.assertFileExists("assets/lib/metadata.txt");
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.xzs");
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.txt");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo2.so");
  }

  @Test
  public void testCompressAssetLibsNoPackageModular() throws IOException {
    String target = "//apps/sample:app_cxx_lib_asset_no_package_modular";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.xzs");
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.txt");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_libasset2.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");

    zipInspector.assertFileDoesNotExist("assets/lib/libs.xzs");
    zipInspector.assertFileDoesNotExist("assets/lib/metadata.txt");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset2.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo2.so");
  }

  @Test
  public void testCompressLibsNoPackageModular() throws IOException {
    String target = "//apps/sample:app_cxx_lib_no_package_modular";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("assets/native.cxx.foo1/libs.xzs");
    zipInspector.assertFileExists("assets/native.cxx.foo1/libs.txt");
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.xzs");
    zipInspector.assertFileExists("assets/native.cxx.libasset/libs.txt");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_libasset2.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");

    zipInspector.assertFileDoesNotExist("assets/lib/libs.xzs");
    zipInspector.assertFileDoesNotExist("assets/lib/metadata.txt");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset2.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_foo2.so");
  }

  /* Disable @Test */
  public void testMultidexProguardModular() throws IOException {
    String target = "//apps/multidex:app_modular_proguard_dontobfuscate";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    String module = "java.com.sample.small.small_with_no_resource_deps";
    zipInspector.assertFileExists("assets/" + module + "/" + module + "2.dex");
  }

  /* Disable @Test */
  public void testMultidexProguardModularWithObfuscation() throws IOException {
    String target = "//apps/multidex:app_modular_proguard_obfuscate";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    String module = "java.com.sample.small.small_with_no_resource_deps";
    zipInspector.assertFileExists("assets/" + module + "/" + module + "2.dex");
  }

  @Test
  public void testLibraryMetadataChecksum() throws IOException {
    String target = "//apps/sample:app_cxx_lib_asset";
    workspace.runBuckCommand("build", target).assertSuccess();
    Path pathToZip =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));
    ZipFile file = new ZipFile(pathToZip.toFile());
    ZipEntry metadata = file.getEntry("assets/lib/metadata.txt");
    assertNotNull(metadata);

    BufferedReader contents =
        new BufferedReader(new InputStreamReader(file.getInputStream(metadata)));
    String line = contents.readLine();
    byte[] buffer = new byte[512];
    while (line != null) {
      // Each line is of the form <filename> <filesize> <SHA256 checksum>
      String[] tokens = line.split(" ");
      assertSame(tokens.length, 3);
      String filename = tokens[0];
      int filesize = Integer.parseInt(tokens[1]);
      String checksum = tokens[2];

      ZipEntry lib = file.getEntry("assets/lib/" + filename);
      assertNotNull(lib);
      InputStream is = file.getInputStream(lib);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      while (filesize > 0) {
        int read = is.read(buffer, 0, Math.min(buffer.length, filesize));
        assertTrue(read >= 0);
        out.write(buffer, 0, read);
        filesize -= read;
      }
      String actualChecksum = Hashing.sha256().hashBytes(out.toByteArray()).toString();
      assertEquals(checksum, actualChecksum);
      is.close();
      out.close();
      line = contents.readLine();
    }
    file.close();
    contents.close();
  }

  @Test
  public void testStripRulesAreShared() throws IOException {
    workspace.runBuckCommand("build", "//apps/sample:app_cxx_lib_asset").assertSuccess();
    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", "//apps/sample:app_cxx_different_rule_name").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    for (BuildTarget target : buildLog.getAllTargets()) {
      String rawTarget = target.toString();
      if (rawTarget.contains("libgnustl_shared.so")) {
        // Stripping the C++ runtime is currently not shared.
        continue;
      }
      if (rawTarget.contains("strip")) {
        buildLog.assertNotTargetBuiltLocally(rawTarget);
      }
    }
  }

  @Test
  public void testApkWithNoResourcesBuildsCorrectly() throws IOException {
    workspace.runBuckBuild("//apps/sample:app_with_no_res").assertSuccess();
    workspace.runBuckBuild("//apps/sample:app_with_no_res_or_predex").assertSuccess();
  }

  @Test
  public void testSimpleAapt2App() throws IOException {
    // TODO(dreiss): Remove this when aapt2 is everywhere.
    ProjectWorkspace.ProcessResult foundAapt2 =
        workspace.runBuckBuild("//apps/sample:check_for_aapt2");
    Assume.assumeTrue(foundAapt2.getExitCode() == 0);

    ImmutableMap<String, Path> outputs =
        workspace.buildMultipleAndReturnOutputs(
            "//apps/sample:app_with_aapt2",
            "//apps/sample:disassemble_app_with_aapt2",
            "//apps/sample:resource_dump_app_with_aapt2");

    ZipInspector zipInspector = new ZipInspector(outputs.get("//apps/sample:app_with_aapt2"));
    zipInspector.assertFileExists("res/drawable/tiny_black.png");
    zipInspector.assertFileExists("res/layout/top_layout.xml");
    zipInspector.assertFileExists("assets/asset_file.txt");

    zipInspector.assertFileIsNotCompressed("res/drawable/tiny_black.png");

    Map<String, String> rDotJavaContents =
        parseRDotJavaSmali(outputs.get("//apps/sample:disassemble_app_with_aapt2"));
    Map<String, String> resourceBundleContents =
        parseResourceDump(outputs.get("//apps/sample:resource_dump_app_with_aapt2"));
    assertEquals(
        resourceBundleContents.get("string/title"),
        rDotJavaContents.get("com/sample2/R$string:title"));
    assertEquals(
        resourceBundleContents.get("layout/top_layout"),
        rDotJavaContents.get("com/sample/R$layout:top_layout"));
    assertEquals(
        resourceBundleContents.get("drawable/app_icon"),
        rDotJavaContents.get("com/sample/R$drawable:app_icon"));
  }

  @Test
  public void testApkEmptyResDirectoriesBuildsCorrectly() throws IOException {
    workspace.runBuckBuild("//apps/sample:app_with_aar_and_no_res").assertSuccess();
  }

  @Test
  public void testNativeLibGeneratedProguardConfigIsUsedByProguard() throws IOException {
    String target = "//apps/sample:app_with_native_lib_proguard";
    workspace.runBuckBuild(target).assertSuccess();

    Path generatedConfig =
        workspace.getPath(
            BuildTargets.getGenPath(
                filesystem,
                BuildTargetFactory.newInstance(target)
                    .withFlavors(AndroidBinaryGraphEnhancer.NATIVE_LIBRARY_PROGUARD_FLAVOR),
                NativeLibraryProguardGenerator.OUTPUT_FORMAT));

    Path proguardDir =
        workspace.getPath(
            BuildTargets.getGenPath(
                filesystem, BuildTargetFactory.newInstance(target), "%s/proguard"));

    Path proguardCommandLine = proguardDir.resolve("command-line.txt");
    // Check that the proguard command line references the native lib proguard config.
    assertTrue(workspace.getFileContents(proguardCommandLine).contains(generatedConfig.toString()));
    assertEquals(
        workspace.getFileContents("native/proguard_gen/expected.pro"),
        workspace.getFileContents(generatedConfig));
  }

  @Test
  public void testReDexIsCalledAppropriatelyFromAndroidBinary() throws IOException {
    Path apk = workspace.buildAndReturnOutput(APP_REDEX_TARGET);
    Path unzippedApk = unzip(apk.getParent(), apk, "app_redex");

    // We use a fake ReDex binary that writes out the arguments it received as JSON so that we can
    // verify that it was called in the right way.
    @SuppressWarnings("unchecked")
    Map<String, Object> userData = ObjectMappers.readValue(unzippedApk.toFile(), Map.class);

    String androidSdk = (String) userData.get("ANDROID_SDK");
    assertTrue(
        "ANDROID_SDK environment variable must be set so ReDex runs with zipalign",
        androidSdk != null && !androidSdk.isEmpty());
    assertEquals(workspace.getDestPath().toString(), userData.get("PWD"));

    assertTrue(userData.get("config").toString().endsWith("apps/sample/redex-config.json"));
    assertEquals("buck-out/gen/apps/sample/app_redex/proguard/seeds.txt", userData.get("keep"));
    assertEquals("my_alias", userData.get("keyalias"));
    assertEquals("android", userData.get("keypass"));
    assertEquals(
        workspace.resolve("keystores/debug.keystore").toString(), userData.get("keystore"));
    assertEquals(
        "buck-out/gen/apps/sample/app_redex__redex/app_redex.redex.apk", userData.get("out"));
    assertEquals("buck-out/gen/apps/sample/app_redex/proguard/command-line.txt", userData.get("P"));
    assertEquals(
        "buck-out/gen/apps/sample/app_redex/proguard/mapping.txt", userData.get("proguard-map"));
    assertTrue((Boolean) userData.get("sign"));
    assertEquals("my_param_name={\"foo\": true}", userData.get("J"));
    assertTrue(
        "redex_extra_args: -j $(location ...) is not properly expanded!",
        userData.get("j").toString().endsWith(".jar"));
    assertTrue(
        "redex_extra_args: -S $(location ...) is not properly expanded!",
        userData.get("S").toString().contains("coldstart_classes=")
            && !userData.get("S").toString().contains("location"));
  }

  @Test
  public void testEditingRedexToolForcesRebuild() throws IOException {
    workspace.runBuckBuild(APP_REDEX_TARGET).assertSuccess();
    workspace.replaceFileContents("tools/redex/fake_redex.py", "main()\n", "main() \n");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(APP_REDEX_TARGET).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(APP_REDEX_TARGET);
  }

  @Test
  public void testEditingSecondaryDexHeadListForcesRebuild() throws IOException {
    workspace.runBuckBuild(APP_REDEX_TARGET).assertSuccess();
    workspace.replaceFileContents("tools/redex/secondary_dex_head.list", "", " ");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(APP_REDEX_TARGET).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(APP_REDEX_TARGET);
  }

  @Test
  public void testInstrumentationApkWithEmptyResDepBuildsCorrectly() throws IOException {
    workspace.runBuckBuild("//apps/sample:instrumentation_apk").assertSuccess();
  }

  @Test
  public void testInvalidKeystoreKeyAlias() throws IOException {
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();

    workspace.replaceFileContents(
        "keystores/debug.keystore.properties", "key.alias=my_alias", "key.alias=invalid_alias");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", SIMPLE_TARGET);
    result.assertFailure("Invalid keystore key alias should fail.");

    assertThat(
        "error message for invalid keystore key alias is incorrect.",
        result.getStderr(),
        containsRegex("The keystore \\[.*\\] key\\.alias \\[.*\\].*does not exist"));
  }

  @Test
  public void testResourcesTrimming() throws IOException {
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();

    // Enable trimming.
    workspace.replaceFileContents(
        "apps/multidex/BUCK", "# ARGS_FOR_APP", "trim_resource_ids = True,  # ARGS_FOR_APP");
    workspace.runBuckCommand("build", "//apps/multidex:disassemble_app_r_dot_java").assertSuccess();
    // Make sure we only see what we expect.
    verifyTrimmedRDotJava(ImmutableSet.of("top_layout", "title"));

    // Make a change.
    workspace.replaceFileContents(
        "java/com/sample/lib/Sample.java", "R.layout.top_layout", "0 /* NO RESOURCE HERE */");

    // Make sure everything gets rebuilt, and we only see what we expect.
    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", "//apps/multidex:disassemble_app_r_dot_java").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//apps/multidex:app#compile_uber_r_dot_java");
    buildLog.assertTargetBuiltLocally("//apps/multidex:app#dex_uber_r_dot_java");
    verifyTrimmedRDotJava(ImmutableSet.of("title"));

    // Turn off trimming and turn on exopackage, and rebuilt.
    workspace.replaceFileContents(
        "apps/multidex/BUCK",
        "trim_resource_ids = True,  # ARGS_FOR_APP",
        "exopackage_modes = ['secondary_dex'],  # ARGS_FOR_APP");
    workspace.runBuckCommand("build", SIMPLE_TARGET).assertSuccess();

    // Make a change.
    workspace.replaceFileContents(
        "java/com/sample/lib/Sample.java", "0 /* NO RESOURCE HERE */", "R.layout.top_layout");

    // rebuilt and verify that we get an ABI hit.
    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", SIMPLE_TARGET).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetHadMatchingInputRuleKey(SIMPLE_TARGET);
  }

  @Test
  public void testResourcesTrimmingWithPattern() throws IOException {
    // Enable trimming.
    workspace.replaceFileContents(
        "apps/multidex/BUCK",
        "# ARGS_FOR_APP",
        "keep_resource_pattern = '^app_.*', trim_resource_ids = True,  # ARGS_FOR_APP");
    workspace.runBuckCommand("build", "//apps/multidex:disassemble_app_r_dot_java").assertSuccess();
    // Make sure we only see what we expect.
    verifyTrimmedRDotJava(ImmutableSet.of("app_icon", "app_name", "top_layout", "title"));

    // Make a change.
    workspace.replaceFileContents(
        "java/com/sample/lib/Sample.java", "R.layout.top_layout", "0 /* NO RESOURCE HERE */");

    // Make sure everything gets rebuilt, and we only see what we expect.
    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", "//apps/multidex:disassemble_app_r_dot_java").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//apps/multidex:app#compile_uber_r_dot_java");
    buildLog.assertTargetBuiltLocally("//apps/multidex:app#dex_uber_r_dot_java");
    verifyTrimmedRDotJava(ImmutableSet.of("app_icon", "app_name", "title"));
  }

  private static final Pattern SMALI_PUBLIC_CLASS_PATTERN =
      Pattern.compile("\\.class public L([\\w/$]+);");
  private static final Pattern SMALI_STATIC_FINAL_INT_PATTERN =
      Pattern.compile("\\.field public static final (\\w+):I = (0x[0-9A-fa-f]+)");

  private void verifyTrimmedRDotJava(ImmutableSet<String> expected) throws IOException {
    List<String> lines =
        filesystem.readLines(
            Paths.get("buck-out/gen/apps/multidex/disassemble_app_r_dot_java/all_r_fields.smali"));

    ImmutableSet.Builder<String> found = ImmutableSet.builder();
    for (String line : lines) {
      Matcher m = SMALI_STATIC_FINAL_INT_PATTERN.matcher(line);
      assertTrue("Could not match line: " + line, m.matches());
      assertThat(m.group(1), IsIn.in(expected));
      found.add(m.group(1));
    }
    assertEquals(expected, found.build());
  }

  private Map<String, String> parseRDotJavaSmali(Path smaliPath) throws IOException {
    List<String> lines = filesystem.readLines(smaliPath);
    ImmutableMap.Builder<String, String> output = ImmutableMap.builder();
    String currentClass = null;
    for (String line : lines) {
      Matcher m;

      m = SMALI_PUBLIC_CLASS_PATTERN.matcher(line);
      if (m.matches()) {
        currentClass = m.group(1);
        continue;
      }

      m = SMALI_STATIC_FINAL_INT_PATTERN.matcher(line);
      if (m.matches()) {
        output.put(currentClass + ":" + m.group(1), m.group(2));
        continue;
      }
    }
    return output.build();
  }

  private static final Pattern RESOURCE_DUMP_SPEC_PATTERN =
      Pattern.compile(" *spec resource (0x[0-9A-fa-f]+) [\\w.]+:(\\w+/\\w+):.*");

  private Map<String, String> parseResourceDump(Path dumpPath) throws IOException {
    List<String> lines = filesystem.readLines(dumpPath);
    ImmutableMap.Builder<String, String> output = ImmutableMap.builder();
    for (String line : lines) {
      Matcher m = RESOURCE_DUMP_SPEC_PATTERN.matcher(line);
      if (m.matches()) {
        output.put(m.group(2), m.group(1));
      }
    }
    return output.build();
  }
}
