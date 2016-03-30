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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.relinker.Symbols;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.DefaultPropertyFinder;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.zip.ZipConstants;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;

import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.hamcrest.Matchers;
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
import java.nio.file.StandardCopyOption;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class AndroidBinaryIntegrationTest {

  @ClassRule
  public static TemporaryPaths projectFolderWithPrebuiltTargets = new TemporaryPaths();

  @Rule
  public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  private static final String SIMPLE_TARGET = "//apps/multidex:app";
  private static final String RAW_DEX_TARGET = "//apps/multidex:app-art";

  @BeforeClass
  public static void setUpOnce() throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        new AndroidBinaryIntegrationTest(),
        "android_project",
        projectFolderWithPrebuiltTargets);
    workspace.setUp();
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();
  }

  @Before
  public void setUp() throws IOException {
    workspace = new ProjectWorkspace(
        projectFolderWithPrebuiltTargets.getRoot(), tmpFolder.getRoot());
    workspace.setUp();
  }

  @Test
  public void testNonExopackageHasSecondary() throws IOException {
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(BuildTargetFactory.newInstance(SIMPLE_TARGET), "%s.apk")));
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
            BuildTargets.getGenPath(BuildTargetFactory.newInstance(RAW_DEX_TARGET), "%s.apk")));
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
            BuildTargets.getGenPath(BuildTargetFactory.newInstance(SIMPLE_TARGET), "%s.apk")));

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
  public void testCxxLibraryDep() throws IOException {
    String target = "//apps/sample:app_cxx_lib_dep";
    workspace.runBuckCommand("build", target).assertSuccess();

    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("lib/armeabi/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/armeabi/libgnustl_shared.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libgnustl_shared.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/x86/libgnustl_shared.so");
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
            BuildTargets.getGenPath(BuildTargetFactory.newInstance(target), "%s.apk")));
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
            BuildTargets.getGenPath(BuildTargetFactory.newInstance(target), "%s.apk")));
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
            BuildTargets.getGenPath(BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo1.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_foo2.so");
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_bar.so");
  }

  private Path unzip(Path tmpDir, Path zipPath, String name) throws IOException {
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
  public void testNativeRelinker() throws IOException, InterruptedException {
    // TODO(cjhopman): is this really the simplest way to get the objdump tool?
    AndroidDirectoryResolver androidResolver = new DefaultAndroidDirectoryResolver(
        workspace.asCell().getFilesystem(),
        Optional.<String>absent(),
        Optional.<String>absent(),
        new DefaultPropertyFinder(
            workspace.asCell().getFilesystem(),
            ImmutableMap.copyOf(System.getenv())));

    Optional<Path> ndkPath = androidResolver.findAndroidNdkDir();
    assertTrue(ndkPath.isPresent());

    ImmutableCollection<NdkCxxPlatform> platforms = NdkCxxPlatforms.getPlatforms(
        new ProjectFilesystem(ndkPath.get()),
        NdkCxxPlatformCompiler.builder()
            .setType(NdkCxxPlatforms.DEFAULT_COMPILER_TYPE)
            .setVersion(NdkCxxPlatforms.DEFAULT_GCC_VERSION)
            .setGccVersion(NdkCxxPlatforms.DEFAULT_GCC_VERSION)
            .build(),
        NdkCxxPlatforms.DEFAULT_CXX_RUNTIME,
        NdkCxxPlatforms.DEFAULT_TARGET_APP_PLATFORM,
        NdkCxxPlatforms.DEFAULT_CPU_ABIS,
        Platform.detect()).values();
    assertFalse(platforms.isEmpty());
    NdkCxxPlatform platform = platforms.iterator().next();

    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );

    Path apkPath = workspace.buildAndReturnOutput("//apps/sample:app_xdso_dce");

    ZipInspector zipInspector = new ZipInspector(apkPath);
    zipInspector.assertFileExists("lib/x86/libnative_xdsodce_top.so");
    zipInspector.assertFileExists("lib/x86/libnative_xdsodce_mid.so");
    zipInspector.assertFileExists("lib/x86/libnative_xdsodce_bot.so");

    Path tmpDir = tmpFolder.newFolder("xdso");
    Path lib = unzip(
        tmpDir, apkPath, "lib/x86/libnative_xdsodce_top.so");
    Symbols sym = Symbols.getSymbols(platform.getObjdump(), pathResolver, lib);

    assertTrue(sym.global.contains("_Z10JNI_OnLoadii"));
    assertTrue(sym.undefined.contains("_Z10midFromTopi"));
    assertTrue(sym.undefined.contains("_Z10botFromTopi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    lib = unzip(tmpDir, apkPath, "lib/x86/libnative_xdsodce_mid.so");
    sym = Symbols.getSymbols(platform.getObjdump(), pathResolver, lib);

    assertTrue(sym.global.contains("_Z10midFromTopi"));
    assertTrue(sym.undefined.contains("_Z10botFromMidi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    lib = unzip(tmpDir, apkPath, "lib/x86/libnative_xdsodce_bot.so");
    sym = Symbols.getSymbols(platform.getObjdump(), pathResolver, lib);

    assertTrue(sym.global.contains("_Z10botFromTopi"));
    assertTrue(sym.global.contains("_Z10botFromMidi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    // Run some verification on the same apk with native_relinker disabled.
    apkPath = workspace.buildAndReturnOutput("//apps/sample:app_no_xdso_dce");
    zipInspector = new ZipInspector(apkPath);
    zipInspector.assertFileExists("lib/x86/libnative_xdsodce_top.so");
    zipInspector.assertFileExists("lib/x86/libnative_xdsodce_mid.so");
    zipInspector.assertFileExists("lib/x86/libnative_xdsodce_bot.so");

    lib = unzip(
        tmpDir, apkPath, "lib/x86/libnative_xdsodce_top.so");
    sym = Symbols.getSymbols(platform.getObjdump(), pathResolver, lib);

    assertTrue(sym.all.contains("_Z6unusedi"));

    lib = unzip(tmpDir, apkPath, "lib/x86/libnative_xdsodce_mid.so");
    sym = Symbols.getSymbols(platform.getObjdump(), pathResolver, lib);

    assertTrue(sym.all.contains("_Z6unusedi"));

    lib = unzip(tmpDir, apkPath, "lib/x86/libnative_xdsodce_bot.so");
    sym = Symbols.getSymbols(platform.getObjdump(), pathResolver, lib);

    assertTrue(sym.all.contains("_Z6unusedi"));
  }


  @Test
  public void testHeaderOnlyCxxLibrary() throws IOException {
    String target = "//apps/sample:app_header_only_cxx_lib_dep";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileDoesNotExist("lib/x86/libnative_cxx_headeronly.so");
  }

  @Test
  public void testX86OnlyCxxLibrary() throws IOException {
    String target = "//apps/sample:app_with_x86_lib";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(BuildTargetFactory.newInstance(target), "%s.apk")));
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
        BuildTargets.getGenPath(BuildTargetFactory.newInstance(target), "%s.apk"));
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
            BuildTargets.getGenPath(BuildTargetFactory.newInstance(target), "%s.apk")));
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
            BuildTargets.getGenPath(BuildTargetFactory.newInstance(target), "%s.apk")));
    zipInspector.assertFileDoesNotExist("assets/lib/x86/libnative_cxx_libasset.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_libasset.so");
  }

  @Test
  public void testCompressAssetLibs() throws IOException {
    String target = "//apps/sample:app_compress_lib_asset";
    workspace.runBuckCommand("build", target).assertSuccess();
    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath(
            BuildTargets.getGenPath(BuildTargetFactory.newInstance(target), "%s.apk")));
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
  public void testLibraryMetadataChecksum() throws IOException {
    String target = "//apps/sample:app_cxx_lib_asset";
    workspace.runBuckCommand("build", target).assertSuccess();
    Path pathToZip = workspace.getPath(
        BuildTargets.getGenPath(BuildTargetFactory.newInstance(target), "%s.apk"));
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
  public void testApkEmptyResDirectoriesBuildsCorrectly() throws IOException {
    workspace.runBuckBuild("//apps/sample:app_no_res").assertSuccess();
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
}
