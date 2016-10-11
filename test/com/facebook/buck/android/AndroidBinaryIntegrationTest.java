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
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultOnDiskBuildInfo;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.zip.ZipConstants;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;

import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.hamcrest.Matchers;
import org.hamcrest.collection.IsIn;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class AndroidBinaryIntegrationTest {

  @ClassRule
  public static TemporaryPaths projectFolderWithPrebuiltTargets = new TemporaryPaths();

  private static ProjectWorkspace workspaceWithPrebuiltTargets;

  @Rule
  public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  private ProjectFilesystem filesystem;

  private static final String SIMPLE_TARGET = "//apps/multidex:app";
  private static final String RAW_DEX_TARGET = "//apps/multidex:app-art";

  @BeforeClass
  public static void setUpOnce() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    workspaceWithPrebuiltTargets = TestDataHelper.createProjectWorkspaceForScenario(
        new AndroidBinaryIntegrationTest(),
        "android_project",
        projectFolderWithPrebuiltTargets);
    workspaceWithPrebuiltTargets.setUp();
    workspaceWithPrebuiltTargets.runBuckBuild(SIMPLE_TARGET).assertSuccess();
  }

  @Before
  public void setUp() throws IOException {
    workspace = ProjectWorkspace.cloneExistingWorkspaceIntoNewFolder(
        workspaceWithPrebuiltTargets,
        tmpFolder);
    workspace.setUp();
    filesystem = new ProjectFilesystem(workspace.getDestPath());
  }

  @Test
  public void testNonExopackageHasSecondary() throws IOException {
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(
                filesystem,
                BuildTargetFactory.newInstance(SIMPLE_TARGET),
                "%s.apk")));
    zipInspector.assertFileExists("assets/secondary-program-dex-jars/metadata.txt");
    zipInspector.assertFileExists("assets/secondary-program-dex-jars/secondary-1.dex.jar");
    zipInspector.assertFileDoesNotExist("classes2.dex");

    zipInspector.assertFileExists("classes.dex");
    zipInspector.assertFileExists("lib/armeabi/libfakenative.so");
  }


  @Test
  public void testRawSplitDexHasSecondary() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", RAW_DEX_TARGET);
    result.assertSuccess();

    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(
                filesystem,
                BuildTargetFactory.newInstance(RAW_DEX_TARGET),
                "%s.apk")));
    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/metadata.txt");

    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/secondary-1.dex.jar");
    zipInspector.assertFileExists("classes2.dex");

    zipInspector.assertFileExists("classes.dex");
    zipInspector.assertFileExists("lib/armeabi/libfakenative.so");
  }

  @Test
  public void testDisguisedExecutableIsRenamed() throws IOException {
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(
                filesystem,
                BuildTargetFactory.newInstance(SIMPLE_TARGET),
                "%s.apk")));

    zipInspector.assertFileExists("lib/armeabi/libmybinary.so");
  }

  @Test
  public void testEditingPrimaryDexClassForcesRebuildForSimplePackage() throws IOException {
    workspace.replaceFileContents(
        "java/com/sample/app/MyApplication.java",
        "package com",
        "package\ncom");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", SIMPLE_TARGET);
    result.assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(SIMPLE_TARGET);
  }

  @Test
  public void testEditingSecondaryDexClassForcesRebuildForSimplePackage() throws IOException {
    workspace.replaceFileContents(
        "java/com/sample/lib/Sample.java",
        "package com",
        "package\ncom");

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
        "java/com/sample/app/MyApplication.java",
        "package com",
        "package\ncom");

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

    workspace.replaceFileContents(
        "java/com/preprocess/convert.py",
        "content=2",
        "content=3");

    outputFile = workspace.buildAndReturnOutput(target);
    output = new String(Files.readAllBytes(outputFile), UTF_8);
    assertThat(output, containsString("content=3"));
  }

  @Test
  public void testDxFindsReferencedResources() throws IOException {
    DefaultOnDiskBuildInfo buildInfo = new DefaultOnDiskBuildInfo(
        BuildTargetFactory.newInstance("//java/com/sample/lib:lib#dex"),
        new ProjectFilesystem(tmpFolder.getRoot()),
        ObjectMappers.newDefaultInstance());
    Optional<ImmutableList<String>> resourcesFromMetadata =
        buildInfo.getValues(DexProducedFromJavaLibrary.REFERENCED_RESOURCES);
    assertTrue(resourcesFromMetadata.isPresent());
    assertEquals(
        ImmutableSet.of("com.sample.top_layout", "com.sample2.title"),
        ImmutableSet.copyOf(resourcesFromMetadata.get()));
  }


  @Test
  public void testCxxLibraryDep() throws IOException {
    String target = "//apps/sample:app_cxx_lib_dep";
    workspace.runBuckCommand("build", target).assertSuccess();

    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
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

    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
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
            "build",
            "-c", "ndk.compiler=clang",
            "-c", "ndk.cxx_runtime=libcxx",
            target);
    result.assertSuccess();

    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(
                filesystem,
                BuildTargetFactory.newInstance(target),
                "%s.apk")));
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

    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("lib/armeabi/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_lib.so");
  }

  @Test
  public void testNoCxxDepsDoesNotIncludeNdkRuntime() throws IOException {
    String target = "//apps/sample:app_no_cxx_deps";
    workspace.runBuckCommand("build", target).assertSuccess();

    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileDoesNotExist("lib/armeabi/libgnustl_shared.so");
    zipInspector.assertFileDoesNotExist("lib/armeabi-v7a/libgnustl_shared.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libgnustl_shared.so");
  }

  @Test
  public void testProguardDontObfuscateGeneratesMappingFile() throws IOException {
    String target = "//apps/sample:app_proguard_dontobfuscate";
    workspace.runBuckCommand("build", target).assertSuccess();

    Path mapping = workspace.getPath(
        BuildTargets.getGenPath(
            filesystem,
            BuildTargetFactory.newInstance(target)
                .withFlavors(AndroidBinaryGraphEnhancer.AAPT_PACKAGE_FLAVOR),
            "__%s__proguard__/.proguard/mapping.txt"));
    assertTrue(Files.exists(mapping));
  }

  @Test
  public void testStaticCxxLibraryDep() throws IOException {
    String target = "//apps/sample:app_static_cxx_lib_dep";
    workspace.runBuckCommand("build", target).assertSuccess();

    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
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
    NdkCxxPlatform platform = getNdkCxxPlatform();
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    Path tmpDir = tmpFolder.newFolder("merging_tmp");
    SymbolGetter syms = new SymbolGetter(tmpDir, platform.getObjdump(), pathResolver);
    SymbolsAndDtNeeded info;

    workspace.replaceFileContents(
        ".buckconfig",
        "#cpu_abis",
        "cpu_abis = x86");
    Map<String, Path> paths = workspace.buildMultipleAndReturnOutputs(
        "//apps/sample:app_with_merged_libs",
        "//apps/sample:app_with_alternate_merge_glue",
        "//apps/sample:app_with_merged_libs_modular"
    );
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

    Path disassembly = workspace.buildAndReturnOutput(
        "//apps/sample:disassemble_app_with_merged_libs_gencode");
    List<String> disassembledLines = filesystem.readLines(disassembly);

    Pattern fieldPattern = Pattern.compile(
        "^\\.field public static final ([^:]+):Ljava/lang/String; = \"([^\"]+)\"$");
    ImmutableMap.Builder<String, String> mapBuilder = ImmutableMap.builder();
    for (String line : disassembledLines) {
      Matcher m = fieldPattern.matcher(line);
      if (!m.matches()) {
        continue;
      }
      mapBuilder.put(m.group(1), m.group(2));
    }

    assertThat(mapBuilder.build(), Matchers.equalTo(ImmutableMap.of(
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
    NdkCxxPlatform platform = getNdkCxxPlatform();
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    Path tmpDir = tmpFolder.newFolder("xdso");
    SymbolGetter syms = new SymbolGetter(tmpDir, platform.getObjdump(), pathResolver);
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

  private NdkCxxPlatform getNdkCxxPlatform() throws IOException, InterruptedException {
    // TODO(cjhopman): is this really the simplest way to get the objdump tool?
    AndroidDirectoryResolver androidResolver = new DefaultAndroidDirectoryResolver(
        workspace.asCell().getRoot().getFileSystem(),
        ImmutableMap.copyOf(System.getenv()),
        Optional.absent(),
        Optional.absent());

    Optional<Path> ndkPath = androidResolver.getNdkOrAbsent();
    assertTrue(ndkPath.isPresent());
    Optional<String> ndkVersion =
      DefaultAndroidDirectoryResolver.findNdkVersionFromDirectory(ndkPath.get());
    String gccVersion = NdkCxxPlatforms.getDefaultGccVersionForNdk(ndkVersion);

    ImmutableCollection<NdkCxxPlatform> platforms = NdkCxxPlatforms.getPlatforms(
        CxxPlatformUtils.DEFAULT_CONFIG,
        ndkPath.get(),
        NdkCxxPlatformCompiler.builder()
            .setType(NdkCxxPlatforms.DEFAULT_COMPILER_TYPE)
            .setVersion(gccVersion)
            .setGccVersion(gccVersion)
            .build(),
        NdkCxxPlatforms.DEFAULT_CXX_RUNTIME,
        NdkCxxPlatforms.DEFAULT_TARGET_APP_PLATFORM,
        NdkCxxPlatforms.DEFAULT_CPU_ABIS,
        Platform.detect()).values();
    assertFalse(platforms.isEmpty());
    return platforms.iterator().next();
  }

  private static class SymbolGetter {
    private final Path tmpDir;
    private final Tool objdump;
    private final SourcePathResolver resolver;

    private SymbolGetter(Path tmpDir, Tool objdump, SourcePathResolver resolver) {
      this.tmpDir = tmpDir;
      this.objdump = objdump;
      this.resolver = resolver;
    }

    private Path unpack(Path apkPath, String libName) throws IOException {
      new ZipInspector(apkPath).assertFileExists(libName);
      return unzip(tmpDir, apkPath, libName);
    }

    Symbols getSymbols(Path apkPath, String libName) throws IOException, InterruptedException {
      Path lib = unpack(apkPath, libName);
      return Symbols.getSymbols(objdump, resolver, lib);
    }

    SymbolsAndDtNeeded getSymbolsAndDtNeeded(Path apkPath, String libName)
        throws IOException, InterruptedException {
      Path lib = unpack(apkPath, libName);
      Symbols symbols = Symbols.getSymbols(objdump, resolver, lib);
      ImmutableSet<String> dtNeeded = Symbols.getDtNeeded(objdump, resolver, lib);
      return new SymbolsAndDtNeeded(symbols, dtNeeded);
    }
  }

  private static class SymbolsAndDtNeeded {
    final Symbols symbols;
    final ImmutableSet<String> dtNeeded;

    private SymbolsAndDtNeeded(Symbols symbols, ImmutableSet<String> dtNeeded) {
      this.symbols = symbols;
      this.dtNeeded = dtNeeded;
    }
  }

  @Test
  public void testHeaderOnlyCxxLibrary() throws IOException {
    String target = "//apps/sample:app_header_only_cxx_lib_dep";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_headeronly.so");
  }

  @Test
  public void testX86OnlyCxxLibrary() throws IOException {
    String target = "//apps/sample:app_with_x86_lib";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
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
    Path apk = workspace.getPath(
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
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
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
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_libasset.so");
  }

  @Test
  public void testCompressAssetLibs() throws IOException {
    String target = "//apps/sample:app_compress_lib_asset";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
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
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
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
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
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
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));
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

  @Test
  public void testLibraryMetadataChecksum() throws IOException {
    String target = "//apps/sample:app_cxx_lib_asset";
    workspace.runBuckCommand("build", target).assertSuccess();
    Path pathToZip = workspace.getPath(
        BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));
    ZipFile file = new ZipFile(pathToZip.toFile());
    ZipEntry metadata = file.getEntry("assets/lib/metadata.txt");
    assertNotNull(metadata);

    BufferedReader contents = new BufferedReader(
        new InputStreamReader(file.getInputStream(metadata)));
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
  public void testApkEmptyResDirectoriesBuildsCorrectly() throws IOException {
    workspace.runBuckBuild("//apps/sample:app_with_aar_and_no_res").assertSuccess();
  }

  @Test
  public void testInstrumentationApkWithEmptyResDepBuildsCorrectly() throws IOException {
    workspace.runBuckBuild("//apps/sample:instrumentation_apk").assertSuccess();
  }

  @Test
  public void testInvalidKeystoreKeyAlias() throws IOException {
    workspace.replaceFileContents(
        "keystores/debug.keystore.properties",
        "key.alias=my_alias",
        "key.alias=invalid_alias"
    );

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
    // Enable trimming.
    workspace.replaceFileContents(
        "apps/multidex/BUCK",
        "# ARGS_FOR_APP",
        "trim_resource_ids = True,  # ARGS_FOR_APP");
    workspace.runBuckCommand("build", "//apps/multidex:disassemble_app_r_dot_java").assertSuccess();
    // Make sure we only see what we expect.
    verifyTrimmedRDotJava(ImmutableSet.of("top_layout", "title"));

    // Make a change.
    workspace.replaceFileContents(
        "java/com/sample/lib/Sample.java",
        "R.layout.top_layout",
        "0 /* NO RESOURCE HERE */");

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
        "java/com/sample/lib/Sample.java",
        "0 /* NO RESOURCE HERE */",
        "R.layout.top_layout");

    // rebuilt and verify that we get an ABI hit.
    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", SIMPLE_TARGET).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetHadMatchingDepsAbi(SIMPLE_TARGET);
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
        "java/com/sample/lib/Sample.java",
        "R.layout.top_layout",
        "0 /* NO RESOURCE HERE */");

    // Make sure everything gets rebuilt, and we only see what we expect.
    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", "//apps/multidex:disassemble_app_r_dot_java").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//apps/multidex:app#compile_uber_r_dot_java");
    buildLog.assertTargetBuiltLocally("//apps/multidex:app#dex_uber_r_dot_java");
    verifyTrimmedRDotJava(ImmutableSet.of("app_icon", "app_name", "title"));
  }

  public static final Pattern SMALI_STATIC_FINAL_INT_PATTERN = Pattern.compile(
      "\\.field public static final (\\w+):I = 0x[0-9A-fa-f]+");

  private void verifyTrimmedRDotJava(ImmutableSet<String> expected) throws IOException {
    List<String> lines = filesystem.readLines(
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
}
