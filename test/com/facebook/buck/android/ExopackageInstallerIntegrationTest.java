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
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.android.common.SdkConstants;
import com.android.ddmlib.InstallException;
import com.facebook.buck.android.agent.util.AgentUtil;
import com.facebook.buck.android.exopackage.ExopackageDevice;
import com.facebook.buck.android.exopackage.ExopackageInstaller;
import com.facebook.buck.android.exopackage.PackageInfo;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ExopackageInfo;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.Hashing;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
  private final Path apkPath = Paths.get("fake.apk");
  private final Path manifestPath = Paths.get("AndroidManifest.xml");
  private final Path dexDirectory = Paths.get("dex-dir");
  private final Path nativeDirectory = Paths.get("native-dir");
  private final Path resourcesDirectory = Paths.get("res-dir");
  private final Path dexManifest = Paths.get("dex.manifest");
  private final Path nativeManifest = Paths.get("native.manifest");
  private final Path agentPath = Paths.get("agent.apk");
  private final Path apkDevicePath = Paths.get("/data/app/Fake.apk");

  private ExoState currentBuildState;
  private ProjectFilesystem filesystem;
  private ExecutionContext executionContext;
  private TestExopackageDevice device;
  private String apkVersionCode;

  @Before
  public void setUp() throws Exception {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    filesystem = new ProjectFilesystem(tmpFolder.getRoot());
    executionContext = TestExecutionContext.newInstance();
    currentBuildState = null;
    filesystem.mkdirs(dexDirectory);
    filesystem.mkdirs(nativeDirectory);
    filesystem.mkdirs(resourcesDirectory);
    device = new TestExopackageDevice();
    apkVersionCode = "1";
    device.abi = SdkConstants.ABI_ARMEABI_V7A;
  }

  @Test
  public void testExoJavaInstall() throws Exception {
    currentBuildState =
        new ExoState(
            "apk-content\n",
            createFakeManifest("manifest-content\n"),
            ImmutableList.of("secondary-dex0\n", "secondary-dex1\n"),
            ImmutableSortedMap.of(),
            ImmutableList.of());

    checkExoInstall(1, 2, 0, 0);
  }

  @Test
  public void testExoNativeInstall() throws Exception {
    device.abi = SdkConstants.ABI_ARMEABI_V7A;
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
            ImmutableList.of());

    checkExoInstall(1, 0, 2, 0);
    // This should be checked already, but do it explicitly here too to make it clear
    // that we actually verify that the correct architecture libs are installed.
    assertTrue(
        device.deviceState.containsKey(
            INSTALL_ROOT.resolve("native-libs/armeabi-v7a/metadata.txt").toString()));
    assertFalse(
        device.deviceState.containsKey(
            INSTALL_ROOT.resolve("native-libs/x86/metadata.txt").toString()));
  }

  @Test
  public void testExoResourcesInstall() throws Exception {
    device.abi = SdkConstants.ABI_ARMEABI_V7A;
    currentBuildState =
        new ExoState(
            "apk-content\n",
            createFakeManifest("manifest-content\n"),
            ImmutableList.of(),
            ImmutableSortedMap.of(),
            ImmutableList.of("exo-resources.apk\n", "exo-assets0\n", "exo-assets1\n"));

    checkExoInstall(1, 0, 0, 3);
  }

  @Test
  public void testExoNativeX86Install() throws Exception {
    device.abi = SdkConstants.ABI_INTEL_ATOM;
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
            ImmutableList.of());

    checkExoInstall(1, 0, 2, 0);
    // This should be checked already, but do it explicitly here too to make it clear
    // that we actually verify that the correct architecture libs are installed.
    assertFalse(
        device.deviceState.containsKey(
            INSTALL_ROOT.resolve("native-libs/armeabi-v7a/metadata.txt").toString()));
    assertTrue(
        device.deviceState.containsKey(
            INSTALL_ROOT.resolve("native-libs/x86/metadata.txt").toString()));
  }

  @Test
  public void testExoFullInstall() throws Exception {
    device.abi = SdkConstants.ABI_ARMEABI_V7A;
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3);
  }

  @Test
  public void testExoNoopReinstall() throws Exception {
    device.abi = SdkConstants.ABI_ARMEABI_V7A;
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3);
    checkExoInstall(0, 0, 0, 0);
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
            ImmutableList.of("exo-resources.apk\n", "exo-assets0\n", "exo-assets1\n"));
  }

  @Test
  public void testExoReinstallWithApkChange() throws Exception {
    device.abi = SdkConstants.ABI_ARMEABI_V7A;
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3);

    currentBuildState =
        new ExoState(
            "new-apk-content\n",
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesContents);

    checkExoInstall(1, 0, 0, 0);
  }

  @Test
  public void testExoReinstallWithJavaChange() throws Exception {
    device.abi = SdkConstants.ABI_ARMEABI_V7A;
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            ImmutableList.of("secondary-dex0\n", "new-secondary-dex1\n"),
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesContents);

    checkExoInstall(0, 1, 0, 0);
  }

  @Test
  public void testExoReinstallWithNativeChange() throws Exception {
    device.abi = SdkConstants.ABI_ARMEABI_V7A;
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3);

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
            currentBuildState.resourcesContents);

    checkExoInstall(0, 0, 1, 0);
  }

  @Test
  public void testExoReinstallWithResourcesChange() throws Exception {
    device.abi = SdkConstants.ABI_ARMEABI_V7A;
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            ImmutableList.of("exo-resources.apk\n", "new-exo-assets0\n", "exo-assets1\n"));

    checkExoInstall(0, 0, 0, 1);
  }

  @Test
  public void testExoReinstallWithAddedDex() throws Exception {
    device.abi = SdkConstants.ABI_ARMEABI_V7A;
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            ImmutableList.of("secondary-dex0\n", "secondary-dex1\n", "secondary-dex2\n"),
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesContents);

    checkExoInstall(0, 1, 0, 0);
  }

  @Test
  public void testExoReinstallWithRemovedDex() throws Exception {
    device.abi = SdkConstants.ABI_ARMEABI_V7A;
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            ImmutableList.of("secondary-dex0\n"),
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesContents);

    checkExoInstall(0, 0, 0, 0);
  }

  @Test
  public void testExoReinstallWithAddedLib() throws Exception {
    device.abi = SdkConstants.ABI_ARMEABI_V7A;
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3);

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
            currentBuildState.resourcesContents);

    checkExoInstall(0, 0, 1, 0);
  }

  @Test
  public void testExoReinstallWithRemovedLib() throws Exception {
    device.abi = SdkConstants.ABI_ARMEABI_V7A;
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            ImmutableSortedMap.of(
                "libs/" + SdkConstants.ABI_INTEL_ATOM + "/libone.so", "x86-libone\n",
                "libs/" + SdkConstants.ABI_ARMEABI_V7A + "/libone.so", "armv7-libone\n"),
            currentBuildState.resourcesContents);

    checkExoInstall(0, 0, 0, 0);
  }

  @Test
  public void testExoReinstallWithRenamedLib() throws Exception {
    device.abi = SdkConstants.ABI_ARMEABI_V7A;
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3);

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
            currentBuildState.resourcesContents);

    // TODO(cjhopman): fix exo install when library is renamed but content remains the same.
    // checkExoInstall(0, 0, 1, 0);
  }

  @Test
  public void testExoReinstallWithAssetsAdded() throws Exception {
    device.abi = SdkConstants.ABI_ARMEABI_V7A;
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            ImmutableList.of(
                "exo-resources.apk\n", "exo-assets0\n", "exo-assets1\n", "exo-assets2\n"));

    checkExoInstall(0, 0, 0, 1);
  }

  @Test
  public void testExoReinstallWithAssetsRemoved() throws Exception {
    device.abi = SdkConstants.ABI_ARMEABI_V7A;
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3);

    currentBuildState =
        new ExoState(
            currentBuildState.apkContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            ImmutableList.of("exo-resources.apk\n", "exo-assets0\n"));

    checkExoInstall(0, 0, 0, 0);
  }

  /**
   * This simulates the state of a real device enough that we can verify that exo installation
   * happens correctly.
   */
  private class TestExopackageDevice implements ExopackageDevice {
    public String abi;
    // Persistent "device" state.
    private NavigableMap<String, String> deviceState;
    private Set<Path> directories;

    private Optional<PackageInfo> deviceAgentPackageInfo;
    private Optional<PackageInfo> fakePackageInfo;
    private String packageSignature;

    // Per install state.
    private int allowedInstalledApks;
    private int allowedInstalledDexes;
    private int allowedInstalledLibs;
    private int allowedInstalledResources;

    TestExopackageDevice() {
      deviceState = new TreeMap<>();
      directories = new HashSet<>();
      deviceAgentPackageInfo = Optional.empty();
      fakePackageInfo = Optional.empty();

      allowedInstalledApks = 0;
      allowedInstalledDexes = 0;
      allowedInstalledLibs = 0;
      allowedInstalledResources = 0;
    }

    @Override
    public boolean installApkOnDevice(File apk, boolean installViaSd, boolean quiet) {
      assertTrue(apk.isAbsolute());
      if (apk.equals(filesystem.resolve(agentPath).toFile())) {
        deviceAgentPackageInfo =
            Optional.of(
                new PackageInfo(
                    "/data/app/Agent.apk", "/data/data/whatever", AgentUtil.AGENT_VERSION_CODE));
        return true;
      } else if (apk.equals(filesystem.resolve(apkPath).toFile())) {
        fakePackageInfo =
            Optional.of(
                new PackageInfo(
                    apkDevicePath.toString(), "/data/data/whatever_else", apkVersionCode));
        try {
          deviceState.put(apkDevicePath.toString(), filesystem.computeSha1(apkPath).toString());
          packageSignature = AgentUtil.getJarSignature(apk.toString());
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        allowedInstalledApks--;
        assertTrue(allowedInstalledApks >= 0);
        return true;
      }
      throw new UnsupportedOperationException("apk path=" + apk);
    }

    @Override
    public void stopPackage(String packageName) throws Exception {
      // noop
    }

    @Override
    public Optional<PackageInfo> getPackageInfo(String packageName) throws Exception {
      if (packageName.equals(AgentUtil.AGENT_PACKAGE_NAME)) {
        return deviceAgentPackageInfo;
      } else if (packageName.equals(FAKE_PACKAGE_NAME)) {
        return fakePackageInfo;
      }
      throw new UnsupportedOperationException("Tried to get package info " + packageName);
    }

    @Override
    public void uninstallPackage(String packageName) throws InstallException {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getSignature(String packagePath) throws Exception {
      assertTrue(deviceState.containsKey(packagePath));
      return packageSignature;
    }

    @Override
    public String listDir(String dirPath) throws Exception {
      Set<String> res = new TreeSet<>();
      for (String s : deviceState.subMap(dirPath, false, dirPath + "\u00FF", false).keySet()) {
        s = s.substring(dirPath.length() + 1);
        if (s.contains("/")) {
          res.add(s.substring(0, s.indexOf("/")));
        } else {
          res.add(s);
        }
      }
      String output = Joiner.on("\n").join(res) + "\n";
      debug("ls " + dirPath + "\n" + output);
      return output;
    }

    @Override
    public void rmFiles(String dirPath, Iterable<String> filesToDelete) throws Exception {
      debug("rmfiles dir=" + dirPath + " files=" + ImmutableList.copyOf(filesToDelete));
      for (String s : filesToDelete) {
        deviceState.remove(dirPath + "/" + s);
      }
    }

    @Override
    public AutoCloseable createForward() throws Exception {
      // TODO(cjhopman): track correct forwarding usage
      return () -> {};
    }

    @Override
    public void installFile(Path targetDevicePath, Path source) throws Exception {
      // TODO(cjhopman): verify port and agentCommand
      assertTrue(targetDevicePath.isAbsolute());
      assertTrue(source.isAbsolute());
      assertTrue(
          String.format(
              "Exopackage should only install files to the install root (%s, %s)",
              INSTALL_ROOT, targetDevicePath),
          targetDevicePath.startsWith(INSTALL_ROOT));
      MoreAsserts.assertContainsOne(directories, targetDevicePath.getParent());
      debug("installing " + targetDevicePath);
      deviceState.put(targetDevicePath.toString(), filesystem.readFileIfItExists(source).get());

      targetDevicePath = INSTALL_ROOT.relativize(targetDevicePath);
      if (targetDevicePath.startsWith(ExopackageInstaller.SECONDARY_DEX_DIR)) {
        if (!targetDevicePath.getFileName().equals(Paths.get("metadata.txt"))) {
          allowedInstalledDexes--;
          assertTrue(allowedInstalledDexes >= 0);
        }
      } else if (targetDevicePath.startsWith(ExopackageInstaller.NATIVE_LIBS_DIR)) {
        if (!targetDevicePath.getFileName().equals(Paths.get("metadata.txt"))) {
          allowedInstalledLibs--;
          assertTrue(allowedInstalledLibs >= 0);
        }
      } else if (targetDevicePath.startsWith(ExopackageInstaller.RESOURCES_DIR)) {
        if (!targetDevicePath.getFileName().equals(Paths.get("metadata.txt"))) {
          allowedInstalledResources--;
          assertTrue(allowedInstalledResources >= 0);
        }
      } else {
        fail("Unrecognized target path (" + targetDevicePath + ")");
      }
    }

    @Override
    public void mkDirP(String dir) throws Exception {
      Path dirPath = Paths.get(dir);
      while (dirPath != null) {
        directories.add(dirPath);
        dirPath = dirPath.getParent();
      }
    }

    @Override
    public String getProperty(String name) throws Exception {
      switch (name) {
        case "ro.build.version.sdk":
          return "20";
      }
      throw new UnsupportedOperationException("Tried to get prop " + name);
    }

    @Override
    public List<String> getDeviceAbis() throws Exception {
      return ImmutableList.of(abi);
    }

    public void setAllowedInstallCounts(
        int expectedApksInstalled,
        int expectedDexesInstalled,
        int expectedLibsInstalled,
        int expectedResourcesInstalled) {
      this.allowedInstalledApks = expectedApksInstalled;
      this.allowedInstalledDexes = expectedDexesInstalled;
      this.allowedInstalledLibs = expectedLibsInstalled;
      this.allowedInstalledResources = expectedResourcesInstalled;
    }
  }

  private void debug(String msg) {
    if (DEBUG) {
      System.out.println("DBG: " + msg);
    }
  }

  private class FakeApkRule extends FakeBuildRule implements HasInstallableApk {
    private ApkInfo apkInfo;

    public FakeApkRule(SourcePathResolver resolver, ApkInfo apkInfo) {
      super(BuildTargetFactory.newInstance("//fake-apk-rule:apk"), filesystem, resolver);
      this.apkInfo = apkInfo;
    }

    @Override
    public ApkInfo getApkInfo() {
      return apkInfo;
    }
  }

  private class FakeAdbInterface implements ExopackageInstaller.AdbInterface {
    @Override
    public boolean adbCall(String description, AdbCallable func, boolean quiet)
        throws InterruptedException {
      try {
        return func.apply(device);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
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
    Map<String, String> expectedState = new TreeMap<>();

    void addApk(Path devicePath, Path hostPath) throws IOException {
      expectedState.put(devicePath.toString(), filesystem.computeSha1(hostPath).toString());
    }

    void addExoFile(String devicePath, String content) {
      expectedState.put(INSTALL_ROOT.resolve(devicePath).toString(), content);
    }
  }

  private void checkExoInstall(
      int expectedApksInstalled,
      int expectedDexesInstalled,
      int expectedLibsInstalled,
      int expectedResourcesInstalled)
      throws Exception {
    SourcePathResolver pathResolver = new SourcePathResolver(null);

    ExpectedStateBuilder builder = new ExpectedStateBuilder();

    writeFakeApk(currentBuildState.apkContent);
    writeFile(manifestPath, currentBuildState.manifestContent);

    builder.addApk(apkDevicePath, apkPath);

    SourcePath apkSourcePath = new PathSourcePath(filesystem, apkPath);
    SourcePath manifestSourcePath = new PathSourcePath(filesystem, manifestPath);

    Optional<ExopackageInfo.DexInfo> dexInfo = Optional.empty();
    ImmutableList<String> dexesContents = currentBuildState.secondaryDexesContents;
    if (!dexesContents.isEmpty()) {
      filesystem.deleteRecursivelyIfExists(dexDirectory);
      String dexMetadata = "";
      String prefix = "";
      for (int i = 0; i < dexesContents.size(); i++) {
        String filename = "secondary-" + i + ".dex.jar";
        String dexContent = dexesContents.get(i);
        writeFile(dexDirectory.resolve(filename), dexContent);
        Sha1HashCode dexHash = filesystem.computeSha1(dexDirectory.resolve(filename));
        dexMetadata += prefix + filename + " " + dexHash;
        prefix = "\n";
        builder.addExoFile("secondary-dex/secondary-" + dexHash + ".dex.jar", dexContent);
      }
      writeFile(dexManifest, dexMetadata);
      dexInfo = Optional.of(ExopackageInfo.DexInfo.of(dexManifest, dexDirectory));

      builder.addExoFile("secondary-dex/metadata.txt", dexMetadata);
    }

    Optional<ExopackageInfo.NativeLibsInfo> nativeLibsInfo = Optional.empty();
    ImmutableSortedMap<String, String> libsContents = currentBuildState.nativeLibsContents;
    if (!libsContents.isEmpty()) {
      filesystem.deleteRecursivelyIfExists(nativeDirectory);
      String expectedMetadata = "";
      String prefix = "";
      for (String k : libsContents.keySet()) {
        Path libPath = nativeDirectory.resolve(k);
        writeFile(libPath, libsContents.get(k));
        if (k.startsWith("libs/" + device.abi)) {
          Sha1HashCode libHash = filesystem.computeSha1(libPath);
          builder.addExoFile(
              "native-libs/" + device.abi + "/native-" + libHash + ".so", libsContents.get(k));
          expectedMetadata +=
              prefix
                  + k.substring(k.lastIndexOf("/") + 1, k.length() - 3)
                  + " native-"
                  + libHash
                  + ".so";
          prefix = "\n";
        }
      }
      CopyNativeLibraries.createMetadataStep(filesystem, nativeManifest, nativeDirectory)
          .execute(executionContext);
      nativeLibsInfo =
          Optional.of(ExopackageInfo.NativeLibsInfo.of(nativeManifest, nativeDirectory));
      builder.addExoFile("native-libs/" + device.abi + "/metadata.txt", expectedMetadata);
    }

    Optional<ExopackageInfo.ResourcesInfo> resourcesInfo = Optional.empty();
    if (!currentBuildState.resourcesContents.isEmpty()) {
      ExopackageInfo.ResourcesInfo.Builder resourcesInfoBuilder =
          ExopackageInfo.ResourcesInfo.builder();
      int n = 0;
      Iterator<String> resourcesContents = currentBuildState.resourcesContents.iterator();
      String expectedMetadata = "";
      String prefix = "";
      while (resourcesContents.hasNext()) {
        Path resourcePath = resourcesDirectory.resolve("resources-" + n++ + ".apk");
        String content = resourcesContents.next();
        writeFile(resourcePath, content);
        resourcesInfoBuilder.addResourcesPaths(new PathSourcePath(filesystem, resourcePath));
        Sha1HashCode resourceHash = filesystem.computeSha1(resourcePath);
        expectedMetadata += prefix + "resources " + resourceHash;
        prefix = "\n";
        builder.addExoFile("resources/" + resourceHash + ".apk", content);
      }
      resourcesInfo = Optional.of(resourcesInfoBuilder.build());
      builder.addExoFile("resources/metadata.txt", expectedMetadata);
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
                    .build())
            .build();
    device.setAllowedInstallCounts(
        expectedApksInstalled,
        expectedDexesInstalled,
        expectedLibsInstalled,
        expectedResourcesInstalled);
    try {
      assertTrue(
          new ExopackageInstaller(
                  pathResolver,
                  executionContext,
                  new FakeAdbInterface(),
                  new FakeApkRule(pathResolver, apkInfo))
              .install(true));
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    assertEquals(builder.expectedState, device.deviceState);
    assertEquals("apk should be installed but wasn't", 0, device.allowedInstalledApks);
    assertEquals("fewer dexes installed than expected", 0, device.allowedInstalledDexes);
    assertEquals("fewer libs installed than expected", 0, device.allowedInstalledLibs);
    assertEquals("fewer resources installed than expected", 0, device.allowedInstalledResources);
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
    try {
      ZipScrubberStep.of(filesystem.resolve(apkPath)).execute(executionContext);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
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

    public ExoState(
        String apkContent,
        String manifestContent,
        ImmutableList<String> secondaryDexesContents,
        ImmutableSortedMap<String, String> nativeLibsContents,
        ImmutableList<String> resourcesContents) {
      this.apkContent = apkContent;
      this.manifestContent = manifestContent;
      this.secondaryDexesContents = secondaryDexesContents;
      this.nativeLibsContents = nativeLibsContents;
      this.resourcesContents = resourcesContents;
    }
  }
}
