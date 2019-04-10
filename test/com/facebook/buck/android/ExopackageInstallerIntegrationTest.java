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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.android.common.SdkConstants;
import com.facebook.buck.android.exopackage.ExopackageInfo;
import com.facebook.buck.android.exopackage.ExopackageInfo.DexInfo;
import com.facebook.buck.android.exopackage.ExopackageInstaller;
import com.facebook.buck.android.exopackage.ExopackagePathAndHash;
import com.facebook.buck.android.exopackage.TestAndroidDevice;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ExopackageInstallerIntegrationTest {
  private static final boolean DEBUG = false;
  private static final String FAKE_PACKAGE_NAME = "buck.exotest.fake";
  private static final Path INSTALL_ROOT =
      ExopackageInstaller.EXOPACKAGE_INSTALL_ROOT.resolve(FAKE_PACKAGE_NAME);

  @Rule public final TemporaryPaths tmpFolder = new TemporaryPaths();
  @Rule public final TemporaryPaths deviceStateDirectory = new TemporaryPaths();
  private final Path apkPath = Paths.get("fake.apk");
  private final Path manifestPath = Paths.get("AndroidManifest.xml");
  private final Path dexDirectory = Paths.get("dex-dir");
  private final Path nativeDirectory = Paths.get("native-dir");
  private final Path resourcesDirectory = Paths.get("res-dir");
  private final Path modulesDirectory = Paths.get("modules-dir");
  private final Path dexManifest = Paths.get("dex.manifest");
  private final Path nativeManifest = Paths.get("native.manifest");
  private final Path agentPath = Paths.get("agent.apk");

  private ExoState currentBuildState;
  private ProjectFilesystem filesystem;
  private ExecutionContext executionContext;
  private TestAndroidDevice testDevice;
  private InstallLimitingAndroidDevice device;
  private String apkVersionCode;

  @Before
  public void setUp() throws Exception {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmpFolder.getRoot());
    executionContext = TestExecutionContext.newInstance();
    currentBuildState = null;
    filesystem.mkdirs(dexDirectory);
    filesystem.mkdirs(nativeDirectory);
    filesystem.mkdirs(resourcesDirectory);
    filesystem.mkdirs(modulesDirectory);
    apkVersionCode = "1";
    setupDeviceWithAbi(SdkConstants.ABI_ARMEABI_V7A);
  }

  // This should be done first in a test case as it doesn't clear the state directory (and we don't
  // expect BUCK to handle a device changing its abi).
  private void setupDeviceWithAbi(String abi) {
    this.testDevice =
        new TestAndroidDevice(
            (apk) -> new TestAndroidDevice.ApkInfo(FAKE_PACKAGE_NAME, apkVersionCode),
            deviceStateDirectory.getRoot(),
            "fake.serial",
            abi);
    this.device =
        new InstallLimitingAndroidDevice(
            testDevice, INSTALL_ROOT, filesystem.resolve(apkPath), filesystem.resolve(agentPath));
  }

  @Test
  public void testExoJavaInstall() throws Exception {
    currentBuildState =
        new ExoState(
            "apk-content\n",
            createFakeManifest("manifest-content\n"),
            ImmutableList.of("secondary-dex0\n", "secondary-dex1\n"),
            ImmutableSortedMap.of(),
            ImmutableList.of(),
            ImmutableList.of());

    checkExoInstall(1, 2, 0, 0, 0);
  }

  @Test
  public void testExoModuleInstall() throws Exception {
    currentBuildState =
        new ExoState(
            "apk-content\n",
            createFakeManifest("manifest-content\n"),
            ImmutableList.of(),
            ImmutableSortedMap.of(),
            ImmutableList.of(),
            ImmutableList.of("module_1\n", "module_2\n"));

    checkExoInstall(1, 0, 0, 0, 2);
  }

  @Test
  public void testExoNativeInstall() throws Exception {
    currentBuildState =
        new ExoState(
            "apk-content\n",
            createFakeManifest("manifest-content\n"),
            ImmutableList.of(),
            ImmutableSortedMap.of(
                "libs/" + SdkConstants.ABI_INTEL_ATOM + "/libone.so", "x86-libone\n",
                "libs/" + SdkConstants.ABI_INTEL_ATOM + "/libtwo.so", "x86-libtwo\n",
                "libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libone.so", "armv7-libone\n",
                "libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libtwo.so", "armv7-libtwo\n"),
            ImmutableList.of(),
            ImmutableList.of());

    checkExoInstall(1, 0, 2, 0, 0);
    // This should be checked already, but do it explicitly here too to make it clear
    // that we actually verify that the correct architecture libs are installed.
    assertTrue(devicePathExists("native-libs/armeabi-v7a/metadata.txt"));
    assertFalse(devicePathExists("native-libs/x86/metadata.txt"));
  }

  private boolean devicePathExists(String path) {
    Path resolved = INSTALL_ROOT.getRoot().relativize(INSTALL_ROOT).resolve(path);
    return deviceStateDirectory.getRoot().resolve(resolved).toFile().exists();
  }

  @Test
  public void testExoResourcesInstall() throws Exception {
    currentBuildState =
        new ExoState(
            "apk-content\n",
            createFakeManifest("manifest-content\n"),
            ImmutableList.of(),
            ImmutableSortedMap.of(),
            ImmutableList.of("exo-resources.apk\n", "exo-assets0\n", "exo-assets1\n"),
            ImmutableList.of());

    checkExoInstall(1, 0, 0, 3, 0);
  }

  @Test
  public void testExoNativeX86Install() throws Exception {
    setupDeviceWithAbi(SdkConstants.ABI_INTEL_ATOM);
    currentBuildState =
        new ExoState(
            "apk-content\n",
            createFakeManifest("manifest-content\n"),
            ImmutableList.of(),
            ImmutableSortedMap.of(
                "libs/" + SdkConstants.ABI_INTEL_ATOM + "/libone.so", "x86-libone\n",
                "libs/" + SdkConstants.ABI_INTEL_ATOM + "/libtwo.so", "x86-libtwo\n",
                "libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libone.so", "armv7-libone\n",
                "libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libtwo.so", "armv7-libtwo\n"),
            ImmutableList.of(),
            ImmutableList.of());

    checkExoInstall(1, 0, 2, 0, 0);
    // This should be checked already, but do it explicitly here too to make it clear
    // that we actually verify that the correct architecture libs are installed.
    assertFalse(devicePathExists("native-libs/armeabi-v7a/metadata.txt"));
    assertTrue(devicePathExists("native-libs/x86/metadata.txt"));
  }

  @Test
  public void testExoFullInstall() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);
  }

  @Test
  public void testExoNoopReinstall() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);
    checkExoInstall(0, 0, 0, 0, 0);
  }

  private void setDefaultFullBuildState() {
    currentBuildState =
        new ExoState(
            "apk-content\n",
            createFakeManifest("manifest-content\n"),
            ImmutableList.of("secondary-dex0\n", "secondary-dex1\n"),
            ImmutableSortedMap.of(
                "libs/" + SdkConstants.ABI_INTEL_ATOM + "/libone.so", "x86-libone\n",
                "libs/" + SdkConstants.ABI_INTEL_ATOM + "/libtwo.so", "x86-libtwo\n",
                "libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libone.so", "armv7-libone\n",
                "libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libtwo.so", "armv7-libtwo\n"),
            ImmutableList.of("exo-resources.apk\n", "exo-assets0\n", "exo-assets1\n"),
            ImmutableList.of("module_1\n", "module_2\n"));
  }

  @Test
  public void testExoReinstallWithApkChange() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            "new-apk-content\n",
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesContents,
            currentBuildState.modularDexesContents);

    checkExoInstall(1, 0, 0, 0, 0);
  }

  @Test
  public void testExoReinstallWithJavaChange() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            ImmutableList.of("secondary-dex0\n", "new-secondary-dex1\n"),
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesContents,
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 1, 0, 0, 0);
  }

  @Test
  public void testExoReinstallWithJavaModuleChange() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesContents,
            ImmutableList.of("module_1\n", "new_module\n"));

    checkExoInstall(0, 0, 0, 0, 1);
  }

  @Test
  public void testExoReinstallWithNativeChange() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            ImmutableSortedMap.of(
                "libs/" + SdkConstants.ABI_INTEL_ATOM + "/libone.so", "x86-libone\n",
                "libs/" + SdkConstants.ABI_INTEL_ATOM + "/libtwo.so", "new-x86-libtwo\n",
                "libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libone.so", "armv7-libone\n",
                "libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libtwo.so", "new-armv7-libtwo\n"),
            currentBuildState.resourcesContents,
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 0, 1, 0, 0);
  }

  @Test
  public void testExoReinstallWithResourcesChange() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            ImmutableList.of("exo-resources.apk\n", "new-exo-assets0\n", "exo-assets1\n"),
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 0, 0, 1, 0);
  }

  @Test
  public void testExoReinstallWithAddedDex() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            ImmutableList.of("secondary-dex0\n", "secondary-dex1\n", "secondary-dex2\n"),
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesContents,
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 1, 0, 0, 0);
  }

  @Test
  public void testExoReinstallWithRemovedDex() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            ImmutableList.of("secondary-dex0\n"),
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesContents,
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 0, 0, 0, 0);
  }

  @Test
  public void testExoReinstallWithAddedLib() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            ImmutableSortedMap.<String, String>naturalOrder()
                .put("libs/" + SdkConstants.ABI_INTEL_ATOM + "/libone.so", "x86-libone\n")
                .put("libs/" + SdkConstants.ABI_INTEL_ATOM + "/libtwo.so", "x86-libtwo\n")
                .put("libs/" + SdkConstants.ABI_INTEL_ATOM + "/libthree.so", "x86-libthree\n")
                .put("libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libone.so", "armv7-libone\n")
                .put("libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libtwo.so", "armv7-libtwo\n")
                .put("libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libthree.so", "armv7-libthree\n")
                .build(),
            currentBuildState.resourcesContents,
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 0, 1, 0, 0);
  }

  @Test
  public void testExoReinstallWithRemovedLib() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            ImmutableSortedMap.of(
                "libs/" + SdkConstants.ABI_INTEL_ATOM + "/libone.so", "x86-libone\n",
                "libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libone.so", "armv7-libone\n"),
            currentBuildState.resourcesContents,
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 0, 0, 0, 0);
  }

  @Test
  public void testExoReinstallWithRenamedLib() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            ImmutableSortedMap.of(
                "libs/" + SdkConstants.ABI_INTEL_ATOM + "/libone.so", "x86-libone\n",
                "libs/" + SdkConstants.ABI_INTEL_ATOM + "/libtwo-new.so", "x86-libtwo\n",
                "libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libone.so", "armv7-libone\n",
                "libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libtwo-new.so", "armv7-libtwo\n"),
            currentBuildState.resourcesContents,
            currentBuildState.modularDexesContents);

    // Note: zero libs installed.  The content is already on device, so only metadata changes.
    checkExoInstall(0, 0, 0, 0, 0);
  }

  @Test
  public void testExoReinstallWithAssetsAdded() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            ImmutableList.of(
                "exo-resources.apk\n", "exo-assets0\n", "exo-assets1\n", "exo-assets2\n"),
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 0, 0, 1, 0);
  }

  @Test
  public void testExoReinstallWithAssetsRemoved() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            ImmutableList.of("exo-resources.apk\n", "exo-assets0\n"),
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 0, 0, 0, 0);
  }

  @Test
  public void testExoInstallWithDuplicateNativeLibs() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            ImmutableSortedMap.of(
                "libs/" + SdkConstants.ABI_INTEL_ATOM + "/libone.so", "libfake\n",
                "libs/" + SdkConstants.ABI_INTEL_ATOM + "/libtwo.so", "libfake\n",
                "libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libone.so", "libfake\n",
                "libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libtwo.so", "libfake\n"),
            currentBuildState.resourcesContents,
            currentBuildState.modularDexesContents);

    // Note: Just one lib installed.  They are identical, so we only install 1.
    checkExoInstall(0, 0, 1, 0, 0);
  }

  private void debug(String msg) {
    if (DEBUG) {
      System.out.println("DBG: " + msg);
    }
  }

  private void writeFile(Path p, String c) {
    try {
      debug("Writing: " + p);
      if (p.getParent() != null) {
        filesystem.mkdirs(p.getParent());
      }
      filesystem.writeContentsToPath(c, p);
    } catch (IOException e) {
      throw new RuntimeException("Failed to write: " + p, e);
    }
  }

  class ExpectedStateBuilder {
    Map<String, String> expectedApkState = new TreeMap<>();
    Map<String, String> expectedFilesState = new TreeMap<>();

    void addApk(String packageName, Path hostPath) throws IOException {
      expectedApkState.put(packageName, filesystem.computeSha1(hostPath).toString());
    }

    void addExoFile(String devicePath, String content) {
      expectedFilesState.put(INSTALL_ROOT.resolve(devicePath).toString(), content);
    }
  }

  private void checkExoInstall(
      int expectedApksInstalled,
      int expectedDexesInstalled,
      int expectedLibsInstalled,
      int expectedResourcesInstalled,
      int expectedModulesInstalled)
      throws Exception {
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(null);

    ExpectedStateBuilder builder = new ExpectedStateBuilder();

    writeFakeApk(currentBuildState.apkContent);
    writeFile(manifestPath, currentBuildState.manifestContent);

    builder.addApk(FAKE_PACKAGE_NAME, apkPath);

    SourcePath apkSourcePath = FakeSourcePath.of(filesystem, apkPath);
    SourcePath manifestSourcePath = FakeSourcePath.of(filesystem, manifestPath);

    Optional<ExopackageInfo.DexInfo> dexInfo = Optional.empty();
    ImmutableList<String> dexesContents = currentBuildState.secondaryDexesContents;
    if (!dexesContents.isEmpty()) {
      filesystem.deleteRecursivelyIfExists(dexDirectory);
      StringBuilder dexMetadata = new StringBuilder();
      String prefix = "";
      for (int i = 0; i < dexesContents.size(); i++) {
        String filename = "secondary-" + i + ".dex.jar";
        String dexContent = dexesContents.get(i);
        writeFile(dexDirectory.resolve(filename), dexContent);
        Sha1HashCode dexHash = filesystem.computeSha1(dexDirectory.resolve(filename));
        dexMetadata.append(prefix).append(filename).append(" ").append(dexHash);
        prefix = "\n";
        builder.addExoFile("secondary-dex/secondary-" + dexHash + ".dex.jar", dexContent);
      }
      writeFile(dexManifest, dexMetadata.toString());
      dexInfo =
          Optional.of(
              ExopackageInfo.DexInfo.of(
                  FakeSourcePath.of(filesystem, dexManifest),
                  FakeSourcePath.of(filesystem, dexDirectory)));

      builder.addExoFile("secondary-dex/metadata.txt", dexMetadata.toString());
    }

    Optional<ExopackageInfo.NativeLibsInfo> nativeLibsInfo = Optional.empty();
    ImmutableSortedMap<String, String> libsContents = currentBuildState.nativeLibsContents;
    if (!libsContents.isEmpty()) {
      filesystem.deleteRecursivelyIfExists(nativeDirectory);
      StringBuilder expectedMetadata = new StringBuilder();
      String prefix = "";
      for (String k : libsContents.keySet()) {
        Path libPath = nativeDirectory.resolve(k);
        writeFile(libPath, libsContents.get(k));
        if (k.startsWith("libs/" + device.getDeviceAbis().get(0))) {
          Sha1HashCode libHash = filesystem.computeSha1(libPath);
          builder.addExoFile(
              "native-libs/" + device.getDeviceAbis().get(0) + "/native-" + libHash + ".so",
              libsContents.get(k));
          expectedMetadata
              .append(prefix)
              .append(k, k.lastIndexOf("/") + 1, k.length() - 3)
              .append(" native-")
              .append(libHash)
              .append(".so");
          prefix = "\n";
        }
      }
      CopyNativeLibraries.createMetadataStep(filesystem, nativeManifest, nativeDirectory)
          .execute(executionContext);
      nativeLibsInfo =
          Optional.of(
              ExopackageInfo.NativeLibsInfo.of(
                  FakeSourcePath.of(filesystem, nativeManifest),
                  FakeSourcePath.of(filesystem, nativeDirectory)));
      builder.addExoFile(
          "native-libs/" + device.getDeviceAbis().get(0) + "/metadata.txt",
          expectedMetadata.toString());
    }

    Optional<ExopackageInfo.ResourcesInfo> resourcesInfo = Optional.empty();
    if (!currentBuildState.resourcesContents.isEmpty()) {
      ExopackageInfo.ResourcesInfo.Builder resourcesInfoBuilder =
          ExopackageInfo.ResourcesInfo.builder();
      int n = 0;
      Iterator<String> resourcesContents = currentBuildState.resourcesContents.iterator();
      StringBuilder expectedMetadata = new StringBuilder();
      String prefix = "";
      while (resourcesContents.hasNext()) {
        String fileName = "resources-" + n++ + ".apk";
        Path resourcePath = resourcesDirectory.resolve(fileName);
        Path hashPath = resourcesDirectory.resolve(fileName + ".hash");
        String content = resourcesContents.next();
        writeFile(resourcePath, content);
        Sha1HashCode resourceHash = filesystem.computeSha1(resourcePath);
        writeFile(hashPath, resourceHash.getHash());
        resourcesInfoBuilder.addResourcesPaths(
            ExopackagePathAndHash.of(
                FakeSourcePath.of(filesystem, resourcePath),
                FakeSourcePath.of(filesystem, hashPath)));
        expectedMetadata.append(prefix).append("resources ").append(resourceHash);
        prefix = "\n";
        builder.addExoFile("resources/" + resourceHash + ".apk", content);
      }
      resourcesInfo = Optional.of(resourcesInfoBuilder.build());
      builder.addExoFile("resources/metadata.txt", expectedMetadata.toString());
    }

    Optional<ImmutableList<DexInfo>> moduleInfo = Optional.empty();
    ImmutableList<String> modularDexesContents = currentBuildState.modularDexesContents;
    if (!modularDexesContents.isEmpty()) {
      ImmutableList.Builder<String> topLevelMetadata = ImmutableList.builder();
      filesystem.deleteRecursivelyIfExists(modulesDirectory);
      Builder<DexInfo> moduleInfoBuilder = ImmutableList.builder();
      for (int i = 0; i < modularDexesContents.size(); i++) {
        String dexContent = modularDexesContents.get(i);
        String moduleName = dexContent.trim();
        String sourceFilename = String.format("%s.dex.jar", moduleName);
        Path moduleDirectory = modulesDirectory.resolve(moduleName);
        moduleDirectory.toFile().mkdirs();
        writeFile(moduleDirectory.resolve(sourceFilename), dexContent);
        Sha1HashCode dexHash = filesystem.computeSha1(moduleDirectory.resolve(sourceFilename));
        String dexJarName = "module-" + dexHash + ".dex.jar";
        builder.addExoFile("modular-dex/" + dexJarName, dexContent);
        // Write the metadata for this module
        Path moduleManifest = moduleDirectory.resolve(moduleName + ".metadata");
        String metadataContents = String.format("%s %s", sourceFilename, dexHash);
        writeFile(moduleManifest, metadataContents);
        builder.addExoFile("modular-dex/" + moduleName + ".metadata", metadataContents);
        moduleInfoBuilder.add(
            ExopackageInfo.DexInfo.of(
                FakeSourcePath.of(filesystem, moduleManifest),
                FakeSourcePath.of(filesystem, moduleDirectory)));
        topLevelMetadata.add(dexJarName + " " + moduleName + "\n");
      }
      builder.addExoFile("modular-dex/metadata.txt", Joiner.on("").join(topLevelMetadata.build()));
      moduleInfo = Optional.of(moduleInfoBuilder.build());
    }

    ApkInfo apkInfo =
        ApkInfo.builder()
            .setApkPath(apkSourcePath)
            .setManifestPath(manifestSourcePath)
            .setExopackageInfo(
                ExopackageInfo.builder()
                    .setDexInfo(dexInfo)
                    .setNativeLibsInfo(nativeLibsInfo)
                    .setResourcesInfo(resourcesInfo)
                    .setModuleInfo(moduleInfo)
                    .build())
            .build();
    device.setAllowedInstallCounts(
        expectedApksInstalled,
        expectedDexesInstalled,
        expectedLibsInstalled,
        expectedResourcesInstalled,
        expectedModulesInstalled);
    try {
      assertTrue(
          new ExopackageInstaller(
                  pathResolver, executionContext, filesystem, FAKE_PACKAGE_NAME, device)
              .doInstall(apkInfo, null));
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    verifyDeviceState(builder);
    device.assertExpectedInstallsAreConsumed();
  }

  private void verifyDeviceState(ExpectedStateBuilder expectedState) throws Exception {
    Map<String, String> installedApks =
        Maps.transformValues(
            testDevice.getInstalledApks(),
            (p -> {
              try {
                return filesystem.computeSha1(p).toString();
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            }));
    assertEquals(expectedState.expectedApkState, installedApks);
    Map<String, String> installedFiles =
        testDevice.getInstalledFiles().entrySet().stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    entry -> entry.getKey().toString(),
                    entry -> {
                      try {
                        return Files.toString(
                            Objects.requireNonNull(entry.getValue()).toFile(), Charsets.UTF_8);
                      } catch (IOException e) {
                        throw new RuntimeException(e);
                      }
                    }));
    assertEquals(expectedState.expectedFilesState, installedFiles);
  }

  private void writeFakeApk(String apkContent) throws IOException {
    String hash = Hashing.sha1().hashString(apkContent, Charsets.US_ASCII).toString();
    try (ZipOutputStream zf =
        new ZipOutputStream(new FileOutputStream(filesystem.resolve(apkPath).toFile()))) {
      ZipEntry signature = new ZipEntry("META-INF/SIG.SF");
      zf.putNextEntry(signature);
      String data = "SHA1-Digest-Manifest: " + hash + "\n";
      zf.write(data.getBytes(Charsets.US_ASCII), 0, data.length());
      zf.closeEntry();

      ZipEntry content = new ZipEntry("content");
      zf.putNextEntry(content);
      zf.write(apkContent.getBytes(Charsets.US_ASCII), 0, apkContent.length());
      zf.closeEntry();
    }
    ZipScrubberStep.of(filesystem.resolve(apkPath)).execute(executionContext);
  }

  private String createFakeManifest(String manifestContent) {
    return "<?xml version='1.0' encoding='utf-8'?>\n"
        + "<manifest\n"
        + "  xmlns:android='http://schemas.android.com/apk/res/android'\n"
        + "  package='"
        + FAKE_PACKAGE_NAME
        + "'\n"
        + "  >\n"
        + "\n"
        + "  <application\n"
        + "    >\n"
        + "     <meta-data>"
        + manifestContent
        + "</meta-data>\n"
        + "  </application>\n"
        + "\n"
        + "</manifest>";
  }

  private class ExoState {
    private final String apkContent;
    private final String manifestContent;
    private final ImmutableList<String> secondaryDexesContents;
    private final ImmutableSortedMap<String, String> nativeLibsContents;
    private final ImmutableList<String> resourcesContents;
    private final ImmutableList<String> modularDexesContents;

    public ExoState(
        String apkContent,
        String manifestContent,
        ImmutableList<String> secondaryDexesContents,
        ImmutableSortedMap<String, String> nativeLibsContents,
        ImmutableList<String> resourcesContents,
        ImmutableList<String> modularDexesContents) {
      this.apkContent = apkContent;
      this.manifestContent = manifestContent;
      this.secondaryDexesContents = secondaryDexesContents;
      this.nativeLibsContents = nativeLibsContents;
      this.resourcesContents = resourcesContents;
      this.modularDexesContents = modularDexesContents;
    }
  }
}
