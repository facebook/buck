/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.relinker.Symbols;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper.SymbolGetter;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper.SymbolsAndDtNeeded;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AndroidBinaryNativeIntegrationTest extends AbiCompilationModeTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  private ProjectWorkspace workspace;

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            new AndroidBinaryNativeIntegrationTest(), "android_project", tmpFolder);
    workspace.setUp();
    setWorkspaceCompilationMode(workspace);
    filesystem = TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
  }

  @Test
  public void testPrebuiltNativeLibraryIsIncluded() throws IOException {
    Path output = workspace.buildAndReturnOutput("//apps/sample:app_with_prebuilt_native_libs");
    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("lib/armeabi/libprebuilt.so");
  }

  @Test
  public void testPrebuiltNativeLibraryAsAssetIsIncluded() throws IOException {
    Path output = workspace.buildAndReturnOutput("//apps/sample:app_with_prebuilt_native_libs");
    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("assets/lib/armeabi/libprebuilt_asset.so");
  }

  @Test
  public void testNativeLibraryMerging() throws IOException, InterruptedException {
    SymbolGetter syms = getSymbolGetter();
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
  public void throwIfLibMergedIntoTwoTargets() throws IOException {
    ProcessResult processResult =
        workspace.runBuckBuild("//apps/sample:app_with_merge_lib_into_two_targets");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        allOf(containsString("attempted to merge"), containsString("into both")));
  }

  @Test
  public void throwIfLibMergedContainsAssetsAndNonAssets() throws IOException {
    ProcessResult processResult =
        workspace.runBuckBuild("//apps/sample:app_with_cross_asset_merged_libs");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(), containsString("contains both asset and non-asset libraries"));
  }

  @Test
  public void throwIfMergeHasCircularDependency() throws IOException {
    ProcessResult processResult =
        workspace.runBuckBuild("//apps/sample:app_with_circular_merged_libs");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("Error: Dependency cycle detected"));
  }

  @Test
  public void throwIfMergedHasCircularDependencyIncludeRoot() throws IOException {
    ProcessResult processResult =
        workspace.runBuckBuild("//apps/sample:app_with_circular_merged_libs_including_root");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("Error: Dependency cycle detected"));
  }

  @Test
  public void throwIfMergedWithInvalidGlue() throws IOException {
    ProcessResult processResult =
        workspace.runBuckBuild("//apps/sample:app_with_invalid_native_lib_merge_glue");
    processResult.assertExitCode(ExitCode.FATAL_GENERIC);
    assertThat(
        processResult.getStderr(),
        allOf(containsString("Native library merge glue"), containsString("is not linkable")));
  }

  @Test
  public void testNativeRelinker() throws IOException, InterruptedException {
    SymbolGetter syms = getSymbolGetter();
    Symbols sym;

    Path apkPath = workspace.buildAndReturnOutput("//apps/sample:app_xdso_dce");

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_top.so");
    assertTrue(sym.global.contains("_Z10JNI_OnLoadii"));
    assertTrue(sym.undefined.contains("_Z10midFromTopi"));
    assertTrue(sym.undefined.contains("_Z10botFromTopi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_mid.so");
    assertTrue(sym.global.contains("_Z10midFromTopi"));
    assertTrue(sym.undefined.contains("_Z10botFromMidi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_bot.so");
    assertTrue(sym.global.contains("_Z10botFromTopi"));
    assertTrue(sym.global.contains("_Z10botFromMidi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    // Run some verification on the same apk with native_relinker disabled.
    apkPath = workspace.buildAndReturnOutput("//apps/sample:app_no_xdso_dce");

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_top.so");
    assertTrue(sym.all.contains("_Z6unusedi"));

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_mid.so");
    assertTrue(sym.all.contains("_Z6unusedi"));

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_bot.so");
    assertTrue(sym.all.contains("_Z6unusedi"));
  }

  @Test
  public void testNativeRelinkerWhitelist() throws IOException, InterruptedException {
    SymbolGetter syms = getSymbolGetter();
    Symbols sym;

    Path apkPath = workspace.buildAndReturnOutput("//apps/sample:app_xdso_dce");

    // The test data has "^_Z12preserved(Bot|Mid)v$" as the only whitelist pattern, so
    // we don't expect preservedTop to survive.
    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_top.so");
    assertFalse(sym.all.contains("_Z12preservedTopv"));

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_mid.so");
    assertTrue(sym.global.contains("_Z12preservedMidv"));

    sym = syms.getDynamicSymbols(apkPath, "lib/x86/libnative_xdsodce_bot.so");
    assertTrue(sym.global.contains("_Z12preservedBotv"));
  }

  @Test
  public void testNativeRelinkerModular() throws IOException, InterruptedException {
    SymbolGetter syms = getSymbolGetter();
    Symbols sym;

    Path apkPath = workspace.buildAndReturnOutput("//apps/sample:app_xdso_dce_modular");

    sym =
        syms.getXzsSymbols(
            apkPath,
            "libnative_xdsodce_top.so",
            "assets/native.xdsodce.top/libs.xzs",
            "assets/native.xdsodce.top/libs.txt");
    assertTrue(sym.global.contains("_Z10JNI_OnLoadii"));
    assertTrue(sym.undefined.contains("_Z10midFromTopi"));
    assertTrue(sym.undefined.contains("_Z10botFromTopi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    sym =
        syms.getXzsSymbols(
            apkPath,
            "libnative_xdsodce_mid.so",
            "assets/native.xdsodce.mid/libs.xzs",
            "assets/native.xdsodce.mid/libs.txt");
    assertTrue(sym.global.contains("_Z10midFromTopi"));
    assertTrue(sym.undefined.contains("_Z10botFromMidi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    sym =
        syms.getXzsSymbols(
            apkPath,
            "libnative_xdsodce_bot.so",
            "assets/native.xdsodce.mid/libs.xzs",
            "assets/native.xdsodce.mid/libs.txt");
    assertTrue(sym.global.contains("_Z10botFromTopi"));
    assertTrue(sym.global.contains("_Z10botFromMidi"));
    assertFalse(sym.all.contains("_Z6unusedi"));
  }

  @Test
  public void testUnstrippedNativeLibraries() throws IOException, InterruptedException {
    workspace.enableDirCache();
    String app = "//apps/sample:app_with_static_symbols";
    String usnl = app + "#unstripped_native_libraries";
    ImmutableMap<String, Path> outputs = workspace.buildMultipleAndReturnOutputs(app, usnl);

    SymbolGetter syms = getSymbolGetter();
    Symbols strippedSyms =
        syms.getNormalSymbols(outputs.get(app), "lib/x86/libnative_cxx_symbols.so");
    assertThat(strippedSyms.all, Matchers.empty());

    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckBuild(usnl);

    String unstrippedPath = null;
    for (String line : filesystem.readLines(workspace.buildAndReturnOutput(usnl))) {
      if (line.matches(".*x86.*cxx_symbols.*")) {
        unstrippedPath = line.trim();
      }
    }
    if (unstrippedPath == null) {
      Assert.fail("Couldn't find path to our x86 library.");
    }

    Symbols unstrippedSyms = syms.getNormalSymbolsFromFile(filesystem.resolve(unstrippedPath));
    assertThat(unstrippedSyms.global, hasItem("get_value"));
    assertThat(unstrippedSyms.all, hasItem("supply_value"));
  }

  @Test
  public void testMergeAndSupportedPlatforms() throws IOException, InterruptedException {
    Path output =
        workspace.buildAndReturnOutput("//apps/sample:app_with_merge_and_supported_platforms");

    SymbolGetter symGetter = getSymbolGetter();
    Symbols syms;

    syms = symGetter.getDynamicSymbols(output, "lib/x86/liball.so");
    assertThat(syms.global, hasItem("_Z3foov"));
    assertThat(syms.global, hasItem("x86_only_function"));
    syms = symGetter.getDynamicSymbols(output, "lib/armeabi-v7a/liball.so");
    assertThat(syms.global, hasItem("_Z3foov"));
    assertThat(syms.global, not(hasItem("x86_only_function")));
  }

  private SymbolGetter getSymbolGetter() throws IOException, InterruptedException {
    NdkCxxPlatform platform = AndroidNdkHelper.getNdkCxxPlatform(filesystem);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    Path tmpDir = tmpFolder.newFolder("symbols_tmp");
    return new SymbolGetter(
        new DefaultProcessExecutor(new TestConsole()), tmpDir, platform.getObjdump(), pathResolver);
  }
}
