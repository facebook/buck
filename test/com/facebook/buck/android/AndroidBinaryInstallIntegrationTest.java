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
import static org.junit.Assert.fail;

import com.android.common.SdkConstants;
import com.facebook.buck.android.exopackage.ExopackageInstaller;
import com.facebook.buck.android.exopackage.TestAndroidDevice;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * These tests verify that the on-device state is properly updated when running `buck install
 * //:some_android_binary`.
 *
 * <p>For both dexes and native libs, one file is pushed for each modified(/added) file.
 *
 * <p>For resources, initial install will always push 3 files. Then, modifying
 * assets/non-main-resources pushes a single file. Modifying main-resources/java_resources will push
 * both the main apk and a single resource file.
 */
@RunWith(Parameterized.class)
public class AndroidBinaryInstallIntegrationTest {
  private static final String FAKE_PACKAGE_NAME = "buck.exotest.fake";
  private static final Path INSTALL_ROOT =
      ExopackageInstaller.EXOPACKAGE_INSTALL_ROOT.resolve(FAKE_PACKAGE_NAME);
  private static final Path CONFIG_PATH = Paths.get("state.config");
  private static final String BINARY_TARGET = "//:binary";
  private static final Path APK_PATH = Paths.get("buck-out/gen/binary.apk");

  @Parameterized.Parameters(name = "concurrentInstall: {0}")
  public static Collection<Object[]> data() {
    return ImmutableList.of(
        new Object[] {AndroidInstallConfig.ConcurrentInstall.ENABLED},
        new Object[] {AndroidInstallConfig.ConcurrentInstall.DISABLED});
  }

  @Parameterized.Parameter(0)
  public AndroidInstallConfig.ConcurrentInstall concurrentInstallType;

  @Rule public final TemporaryPaths tmpFolder = new TemporaryPaths();
  @Rule public final TemporaryPaths deviceStateDirectory = new TemporaryPaths();

  private ProjectWorkspace projectWorkspace;

  private ExoState currentBuildState;
  private ProjectFilesystem filesystem;
  private ExecutionContext executionContext;
  private TestAndroidDevice testDevice;
  private InstallLimitingAndroidDevice installLimiter;
  private String apkVersionCode;

  @Before
  public void setUp() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();

    projectWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "exopackage_integration", tmpFolder);
    projectWorkspace.setUp();
    projectWorkspace.addBuckConfigLocalOption(
        "install", "concurrent_install", concurrentInstallType.toString());
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmpFolder.getRoot());
    executionContext = TestExecutionContext.newInstanceWithRealProcessExecutor();
    currentBuildState = null;
    apkVersionCode = "1";

    Properties properties = System.getProperties();
    properties.setProperty(
        "buck.native_exopackage_fake_path",
        Paths.get("assets/android/native-exopackage-fakes.apk").toAbsolutePath().toString());
    AdbHelper.setDevicesSupplierForTests(Optional.of(() -> ImmutableList.of(installLimiter)));

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
    this.installLimiter =
        new InstallLimitingAndroidDevice(
            testDevice, INSTALL_ROOT, filesystem.resolve(APK_PATH), filesystem.resolve(""));
  }

  @Test
  public void testExoJavaInstall() throws Exception {
    currentBuildState =
        new ExoState(
            "apk_data",
            "manifest_data",
            ImmutableList.of("java1_data1", "java2_data1"),
            ImmutableList.of(),
            ResourcesExoData.empty().withAssetsData("assets"),
            ImmutableList.of());

    checkExoInstall(1, 2, 0, 3, 0);
  }

  @Test
  public void testExoModulesInstall() throws Exception {
    currentBuildState =
        new ExoState(
            "apk_data",
            "manifest_data",
            ImmutableList.of(),
            ImmutableList.of(),
            ResourcesExoData.empty().withAssetsData("assets"),
            ImmutableList.of("module_1", "module_2"));

    checkExoInstall(1, 0, 0, 3, 2);
  }

  @Test
  public void testExoNativeInstall() throws Exception {
    currentBuildState =
        new ExoState(
            "apk_content",
            "manifest_data",
            ImmutableList.of(),
            ImmutableList.of("cxx1_data1", "cxx2_data1"),
            ResourcesExoData.empty().withAssetsData("assets"),
            ImmutableList.of());

    checkExoInstall(1, 0, 2, 3, 0);
  }

  @Test
  public void testExoResourcesInstall() throws Exception {
    currentBuildState =
        new ExoState(
            "apk_content",
            "manifest_data",
            ImmutableList.of(),
            ImmutableList.of(),
            resourcesData(
                "main_resources_data", "resources_data", "assets_data", "java_resources_data"),
            ImmutableList.of());

    checkExoInstall(1, 0, 0, 3, 0);
  }

  private ResourcesExoData resourcesData(
      String mainResourcesData,
      @Nullable String resourcesData,
      @Nullable String assetsData,
      @Nullable String javaResourcesData) {
    return new ResourcesExoData(mainResourcesData, resourcesData, assetsData, javaResourcesData);
  }

  @Test
  public void testExoNativeX86Install() throws Exception {
    setupDeviceWithAbi(SdkConstants.ABI_INTEL_ATOM);
    currentBuildState =
        new ExoState(
            "apk_content",
            "manifest_data",
            ImmutableList.of(),
            ImmutableList.of("data1", "data2"),
            ResourcesExoData.empty().withAssetsData("assets"),
            ImmutableList.of());

    checkExoInstall(1, 0, 2, 3, 0);
  }

  private void setDefaultFullBuildState() {
    currentBuildState =
        new ExoState(
            "apk_content",
            "manifest_data",
            ImmutableList.of("java1_data1", "java2_data1"),
            ImmutableList.of("cxx1_data1", "cxx2_data1"),
            resourcesData("main_resources1", "resources1", "assets1", "java_resources1"),
            ImmutableList.of("module_1", "module_2"));
  }

  @Test
  public void testExoFullInstall() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);
  }

  @Test
  public void testExoFullInstallAfterUninstall() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);
    testDevice.uninstallPackage(FAKE_PACKAGE_NAME);
    testDevice.rmFiles(
        INSTALL_ROOT.toString(),
        testDevice.listDirRecursive(INSTALL_ROOT).stream().map(Path::toString)::iterator);
    checkExoInstall(1, 2, 2, 3, 2);
  }

  @Test
  public void testExoNoopReinstall() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);
    checkExoInstall(0, 0, 0, 0, 0);
  }

  @Test
  public void testExoReinstallWithMainJavaChange() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            "new_apk_content",
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesData,
            currentBuildState.modularDexesContents);

    checkExoInstall(1, 0, 0, 0, 0);
  }

  @Test
  public void testExoReinstallWithJavaChange() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.mainJavaContent,
            currentBuildState.manifestContent,
            ImmutableList.of(
                currentBuildState.secondaryDexesContents.get(0),
                "new_" + currentBuildState.secondaryDexesContents.get(1)),
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesData,
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 1, 0, 0, 0);
  }

  @Test
  public void testExoReinstallWithJavaModuleChange() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.mainJavaContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesData,
            ImmutableList.of("module_1", "new_module"));

    checkExoInstall(0, 0, 0, 0, 1);
  }

  @Test
  public void testExoReinstallWithNativeChange() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.mainJavaContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            ImmutableList.of(
                currentBuildState.nativeLibsContents.get(0),
                "new_" + currentBuildState.nativeLibsContents.get(1)),
            currentBuildState.resourcesData,
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 0, 1, 0, 0);
  }

  @Test
  public void testExoReinstallWithMainResourcesChange() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.mainJavaContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesData.withMainResourcesData("new_data"),
            currentBuildState.modularDexesContents);

    checkExoInstall(1, 0, 0, 1, 0);
  }

  @Test
  public void testExoReinstallWithResourcesChange() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.mainJavaContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            currentBuildState
                .resourcesData
                .withResourcesData("new_resources_data")
                .withAssetsData("new_assets_data"),
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 0, 0, 2, 0);
  }

  @Test
  public void testExoReinstallWithJavaResourcesChange() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.mainJavaContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesData.withJavaResourcesData("new_java_resources_data"),
            currentBuildState.modularDexesContents);

    checkExoInstall(1, 0, 0, 1, 0);
  }

  @Test
  public void testExoReinstallWithAddedDex() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.mainJavaContent,
            currentBuildState.manifestContent,
            ImmutableList.<String>builder()
                .addAll(currentBuildState.secondaryDexesContents)
                .add("another_one")
                .build(),
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesData,
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 1, 0, 0, 0);
  }

  @Test
  public void testExoReinstallWithAddedModule() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.mainJavaContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesData,
            ImmutableList.<String>builder()
                .addAll(currentBuildState.modularDexesContents)
                .add("another_module")
                .build());

    checkExoInstall(1, 0, 0, 0, 1);
  }

  @Test
  public void testExoReinstallWithRemovedDex() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.mainJavaContent,
            currentBuildState.manifestContent,
            ImmutableList.of("java1_data1"),
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesData,
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 0, 0, 0, 0);
  }

  @Test
  public void testExoReinstallWithRemovedModule() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.mainJavaContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesData,
            currentBuildState.modularDexesContents.subList(0, 1));

    checkExoInstall(0, 0, 0, 0, 0);
  }

  @Test
  public void testExoReinstallWithAddedLib() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.mainJavaContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            ImmutableList.<String>builder()
                .addAll(currentBuildState.nativeLibsContents)
                .add("another_one")
                .build(),
            currentBuildState.resourcesData,
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 0, 1, 0, 0);
  }

  @Test
  public void testExoReinstallWithRemovedLib() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.mainJavaContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            ImmutableList.copyOf(currentBuildState.nativeLibsContents.subList(0, 1)),
            currentBuildState.resourcesData,
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 0, 0, 0, 0);
  }

  @Test
  public void testExoReinstallWithAssetsAdded() throws Exception {
    currentBuildState =
        new ExoState(
            "main_java",
            "manifest",
            ImmutableList.of(),
            ImmutableList.of(),
            resourcesData("main_resources", "resources", null, "java_resources"),
            ImmutableList.of());

    checkExoInstall(1, 0, 0, 3, 0);

    currentBuildState =
        new ExoState(
            currentBuildState.mainJavaContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesData.withAssetsData("assets"),
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 0, 0, 1, 0);
  }

  @Test
  public void testExoReinstallWithAssetsRemoved() throws Exception {
    setDefaultFullBuildState();

    checkExoInstall(1, 2, 2, 3, 2);

    currentBuildState =
        new ExoState(
            currentBuildState.mainJavaContent,
            currentBuildState.manifestContent,
            currentBuildState.secondaryDexesContents,
            currentBuildState.nativeLibsContents,
            currentBuildState.resourcesData.withAssetsData(null),
            currentBuildState.modularDexesContents);

    checkExoInstall(0, 0, 0, 1, 0);
  }

  private void checkExoInstall(
      int expectedApksInstalled,
      int expectedDexesInstalled,
      int expectedLibsInstalled,
      int expectedResourcesInstalled,
      int expectedModulesInstalled)
      throws Exception {
    installLimiter.setAllowedInstallCounts(
        expectedApksInstalled,
        expectedDexesInstalled,
        expectedLibsInstalled,
        expectedResourcesInstalled,
        expectedModulesInstalled);
    List<String> javaDeps = new ArrayList<>();
    List<String> cxxDeps = new ArrayList<>();
    List<String> resourceDeps = new ArrayList<>();
    List<String> moduleDeps = new ArrayList<>();

    Map<String, Object> config = new TreeMap<>();
    Map<String, String> data = new TreeMap<>();
    data.put("manifest", currentBuildState.manifestContent);
    data.put("main_apk_resources", currentBuildState.resourcesData.mainResourcesData);
    data.put("main_apk_java", currentBuildState.mainJavaContent);

    // Put a bunch of default values as the generation script requires everything to have a value.
    data.put("java1", "unset");
    data.put("java2", "unset");
    data.put("java3", "unset");
    data.put("java_module1", "unset");
    data.put("java_module2", "unset");
    data.put("java_module3", "unset");
    data.put("cxx1", "unset");
    data.put("cxx2", "unset");
    data.put("cxx3", "unset");
    data.put("resources", "unset");
    data.put("assets", "unset");
    data.put("java_resources", "unset");

    int id = 1;
    for (String content : currentBuildState.secondaryDexesContents) {
      data.put("java" + id, content);
      javaDeps.add(":java" + id);
      id++;
    }

    id = 1;
    for (String content : currentBuildState.nativeLibsContents) {
      data.put("cxx" + id, content);
      cxxDeps.add(":cxx" + id);
      id++;
    }

    if (currentBuildState.resourcesData.assetsData == null
        && currentBuildState.resourcesData.javaResourcesData == null) {
      // TODO(cjhopman): fix this.
      fail("exo-for-resources currently fails if both assets and java resources are empty.");
    }
    if (currentBuildState.resourcesData.resourcesData != null) {
      data.put("resources", currentBuildState.resourcesData.resourcesData);
      resourceDeps.add(":resources");
    }
    if (currentBuildState.resourcesData.assetsData != null) {
      data.put("assets", currentBuildState.resourcesData.assetsData);
      resourceDeps.add(":assets");
    }
    if (currentBuildState.resourcesData.javaResourcesData != null) {
      data.put("java_resources", currentBuildState.resourcesData.javaResourcesData);
      resourceDeps.add(":java_resources");
    }

    id = 1;
    for (String content : currentBuildState.modularDexesContents) {
      data.put("java_module" + id, content);
      moduleDeps.add(":java_module" + id);
      id++;
    }

    ObjectMapper mapper = new ObjectMapper();

    config.put("data", data);
    config.put("exopackage", true);
    config.put("package", FAKE_PACKAGE_NAME);
    config.put("java_deps", javaDeps);
    config.put("cxx_deps", cxxDeps);
    config.put("resources_deps", resourceDeps);
    config.put("module_deps", moduleDeps);
    filesystem.writeContentsToPath(
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config), CONFIG_PATH);

    ProcessExecutor.Result result =
        executionContext
            .getProcessExecutor()
            .launchAndExecute(
                ProcessExecutorParams.builder()
                    .setCommand(ImmutableList.of("python", "generate.py"))
                    .setDirectory(filesystem.getRootPath())
                    .build());
    assertEquals(result.getMessageForUnexpectedResult("File generation"), result.getExitCode(), 0);
    ProcessResult installResult = projectWorkspace.runBuckCommand("install", BINARY_TARGET);
    installResult.assertSuccess();
    installLimiter.assertExpectedInstallsAreConsumed();
  }

  private class ExoState {
    private final String mainJavaContent;
    private final String manifestContent;
    private final ImmutableList<String> secondaryDexesContents;
    private final ImmutableList<String> nativeLibsContents;
    private final ResourcesExoData resourcesData;
    private ImmutableList<String> modularDexesContents;

    public ExoState(
        String mainJavaContent,
        String manifestContent,
        ImmutableList<String> secondaryDexesContents,
        ImmutableList<String> nativeLibsContents,
        ResourcesExoData resourcesData,
        ImmutableList<String> modularDexesContents) {
      this.mainJavaContent = mainJavaContent;
      this.manifestContent = manifestContent;
      this.secondaryDexesContents = secondaryDexesContents;
      this.nativeLibsContents = nativeLibsContents;
      this.resourcesData = resourcesData;
      this.modularDexesContents = modularDexesContents;
    }
  }

  private static class ResourcesExoData {
    private final String mainResourcesData;
    private final String resourcesData;
    private final String assetsData;
    private final String javaResourcesData;

    public ResourcesExoData(
        String mainResourcesData,
        String resourcesData,
        String assetsData,
        String javaResourcesData) {
      this.mainResourcesData = mainResourcesData;
      this.resourcesData = resourcesData;
      this.assetsData = assetsData;
      this.javaResourcesData = javaResourcesData;
    }

    public ResourcesExoData withResourcesData(@Nullable String resourcesData) {
      return new ResourcesExoData(mainResourcesData, resourcesData, assetsData, javaResourcesData);
    }

    public ResourcesExoData withAssetsData(@Nullable String assetsData) {
      return new ResourcesExoData(mainResourcesData, resourcesData, assetsData, javaResourcesData);
    }

    public static ResourcesExoData empty() {
      return new ResourcesExoData("empty_main_data", null, null, null);
    }

    public ResourcesExoData withMainResourcesData(String mainResourcesData) {
      return new ResourcesExoData(mainResourcesData, resourcesData, assetsData, javaResourcesData);
    }

    public ResourcesExoData withJavaResourcesData(String javaResourcesData) {
      return new ResourcesExoData(mainResourcesData, resourcesData, assetsData, javaResourcesData);
    }
  }
}
