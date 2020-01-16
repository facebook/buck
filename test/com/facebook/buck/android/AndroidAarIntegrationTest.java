/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.relinker.Symbols;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper.SymbolGetter;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.facebook.buck.util.unarchive.ExistingFileMode;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidAarIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws Exception {
    AssumeAndroidPlatform.get(TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()))
        .assumeSdkIsAvailable();
  }

  @Test
  public void testBuildAndroidAar() throws InterruptedException, IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_aar_build/caseA", tmp);
    workspace.setUp();
    String target = "//:app";
    workspace.runBuckBuild(target).assertSuccess();

    Path aar =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem(),
                BuildTargetFactory.newInstance(target),
                AndroidAar.AAR_FORMAT));
    ZipInspector zipInspector = new ZipInspector(aar);
    zipInspector.assertFileExists("AndroidManifest.xml");
    zipInspector.assertFileExists("classes.jar");
    zipInspector.assertFileExists("R.txt");
    zipInspector.assertFileExists("assets/a.txt");
    zipInspector.assertFileExists("assets/b.txt");
    zipInspector.assertFileExists("res/raw/helloworld.txt");
    zipInspector.assertFileExists("res/values/values.xml");
    zipInspector.assertFileContents(
        "res/values/values.xml", workspace.getFileContents("res/values/A.xml").trim());

    Path contents = tmp.getRoot().resolve("aar-contents");
    ArchiveFormat.ZIP
        .getUnarchiver()
        .extractArchive(
            new DefaultProjectFilesystemFactory(), aar, contents, ExistingFileMode.OVERWRITE);
    try (JarFile classes = new JarFile(contents.resolve("classes.jar").toFile())) {
      assertThat(classes.getJarEntry("com/example/HelloWorld.class"), Matchers.notNullValue());
    }
  }

  @Test
  public void testBuildAndroidAarWithDerivedDeps() throws InterruptedException, IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_aar_build/caseC", tmp);
    workspace.setUp();
    String target = "//:app";
    workspace.runBuckBuild(target).assertSuccess();

    Path aar =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem(),
                BuildTargetFactory.newInstance(target),
                AndroidAar.AAR_FORMAT));
    ZipInspector zipInspector = new ZipInspector(aar);
    zipInspector.assertFileExists("AndroidManifest.xml");
    zipInspector.assertFileExists("classes.jar");
    zipInspector.assertFileExists("R.txt");
    zipInspector.assertFileExists("assets/a.txt");
    zipInspector.assertFileExists("assets/b.txt");
    zipInspector.assertFileExists("res/raw/helloworld.txt");
    zipInspector.assertFileExists("res/values/values.xml");
    zipInspector.assertFileContents(
        "res/values/values.xml", workspace.getFileContents("res/values/A.xml").trim());

    Path contents = tmp.getRoot().resolve("aar-contents");
    ArchiveFormat.ZIP
        .getUnarchiver()
        .extractArchive(
            new DefaultProjectFilesystemFactory(), aar, contents, ExistingFileMode.OVERWRITE);
    try (JarFile classes = new JarFile(contents.resolve("classes.jar").toFile())) {
      assertThat(classes.getJarEntry("com/example/HelloWorld.class"), Matchers.notNullValue());
    }
  }

  @Test
  public void testBuildConfigNotIncludedInAarByDefault() throws InterruptedException, IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmp);
    workspace.setUp();
    String target = "//apps/aar_build_config:app_without_build_config";
    workspace.runBuckBuild(target).assertSuccess();

    Path aar =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem(),
                BuildTargetFactory.newInstance(target),
                AndroidAar.AAR_FORMAT));

    Path contents = tmp.getRoot().resolve("aar-contents");
    ArchiveFormat.ZIP
        .getUnarchiver()
        .extractArchive(
            new DefaultProjectFilesystemFactory(), aar, contents, ExistingFileMode.OVERWRITE);
    try (JarFile classes = new JarFile(contents.resolve("classes.jar").toFile())) {
      for (JarEntry jarEntry : Collections.list(classes.entries())) {
        String jarEntryName = jarEntry.getName();
        assertThat(
            jarEntryName + " looks like a build_config entry and houldn't be in the jar.",
            jarEntryName,
            Matchers.not(Matchers.stringContainsInOrder("BuildConfig")));
      }
    }
  }

  @Test
  public void testBuildConfigValuesTakenFromAarRule()
      throws ClassNotFoundException, IllegalAccessException, InterruptedException, IOException,
          NoSuchFieldException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmp);
    workspace.setUp();
    String target = "//apps/aar_build_config:app_with_build_config";
    Path aarLocation = workspace.buildAndReturnOutput(target);

    Path contents = tmp.getRoot().resolve("aar-contents");
    ArchiveFormat.ZIP
        .getUnarchiver()
        .extractArchive(
            new DefaultProjectFilesystemFactory(),
            aarLocation,
            contents,
            ExistingFileMode.OVERWRITE);

    URL jarUrl = contents.resolve("classes.jar").toUri().toURL();
    try (URLClassLoader loader = new URLClassLoader(new URL[] {jarUrl})) {
      Class<?> buildConfigClass = loader.loadClass("com.sample.android.BuildConfig");
      Field overriddenField = buildConfigClass.getField("OVERRIDDEN_FIELD");
      assertThat(overriddenField.get(buildConfigClass), Matchers.equalTo("android_aar"));
      Field notOverriddenField = buildConfigClass.getField("NOT_OVERRIDDEN_FIELD");
      assertThat(notOverriddenField.get(buildConfigClass), Matchers.equalTo("value"));
    }
  }

  @Test
  public void testBuildPrebuiltAndroidAar() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_aar_build/caseB", tmp);
    workspace.setUp();
    String target = "//:app";
    workspace.runBuckBuild(target).assertSuccess();

    Path aar =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem(),
                BuildTargetFactory.newInstance(target),
                AndroidAar.AAR_FORMAT));
    ZipInspector zipInspector = new ZipInspector(aar);
    zipInspector.assertFileExists("AndroidManifest.xml");
    zipInspector.assertFileExists("classes.jar");
    zipInspector.assertFileExists("R.txt");
    zipInspector.assertFileExists("res/");
    zipInspector.assertFileExists("res/values/");
    zipInspector.assertFileExists("res/values/values.xml");

    zipInspector.assertFileContents(
        "res/values/values.xml",
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<resources>\n"
            + "    <string name=\"app_name\">Hello World</string>\n"
            + "</resources>");
  }

  @Test
  public void testCxxLibraryDependent() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_aar_native_deps/cxx_deps", tmp);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();
    String target = "//:app";
    workspace.runBuckBuild(target).assertSuccess();

    Path aar =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem(),
                BuildTargetFactory.newInstance(target),
                AndroidAar.AAR_FORMAT));
    ZipInspector zipInspector = new ZipInspector(aar);
    zipInspector.assertFileExists("AndroidManifest.xml");
    zipInspector.assertFileExists("classes.jar");
    zipInspector.assertFileExists("R.txt");
    zipInspector.assertFileExists("res/");
    if (AssumeAndroidPlatform.get(workspace).isArmAvailable()) {
      zipInspector.assertFileExists("jni/armeabi/libdep.so");
      zipInspector.assertFileExists("jni/armeabi/libnative.so");
    }
    zipInspector.assertFileExists("jni/armeabi-v7a/libdep.so");
    zipInspector.assertFileExists("jni/armeabi-v7a/libnative.so");
    zipInspector.assertFileExists("jni/x86/libdep.so");
    zipInspector.assertFileExists("jni/x86/libnative.so");
  }

  @Test
  public void testNativeLibraryDependent() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_aar_native_deps/ndk_deps", tmp);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();
    String target = "//:app";
    workspace.runBuckBuild(target).assertSuccess();

    Path aar =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem(),
                BuildTargetFactory.newInstance(target),
                AndroidAar.AAR_FORMAT));
    ZipInspector zipInspector = new ZipInspector(aar);
    zipInspector.assertFileExists("AndroidManifest.xml");
    zipInspector.assertFileExists("classes.jar");
    zipInspector.assertFileExists("R.txt");
    zipInspector.assertFileExists("res/");
    zipInspector.assertFileExists("assets/lib/armeabi-v7a/libfoo.so");
    zipInspector.assertFileExists("assets/lib/x86/libfoo.so");
    zipInspector.assertFileExists("jni/armeabi-v7a/libbar.so");
    zipInspector.assertFileExists("jni/x86/libbar.so");
  }

  @Test
  public void testNativeLibraryRelinker() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmp);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();
    String target = "//apps/aar_build_config:app_with_relinker";
    workspace.runBuckBuild(target).assertSuccess();
    SymbolGetter syms = AndroidNdkHelper.getSymbolGetter(workspace.getProjectFileSystem(), tmp);
    Symbols sym;

    Path aar =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem(),
                BuildTargetFactory.newInstance(target),
                AndroidAar.AAR_FORMAT));
    sym = syms.getDynamicSymbols(aar, "assets/lib/x86/libnative_xdsodce_top.so");
    assertTrue(sym.global.contains("_Z10JNI_OnLoadii"));
    assertTrue(sym.undefined.contains("_Z10midFromTopi"));
    assertTrue(sym.undefined.contains("_Z10botFromTopi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    sym = syms.getDynamicSymbols(aar, "jni/x86/libnative_xdsodce_mid.so");
    assertTrue(sym.global.contains("_Z10midFromTopi"));
    assertTrue(sym.undefined.contains("_Z10botFromMidi"));
    assertFalse(sym.all.contains("_Z6unusedi"));

    sym = syms.getDynamicSymbols(aar, "jni/x86/libnative_xdsodce_bot.so");
    assertTrue(sym.global.contains("_Z10botFromTopi"));
    assertTrue(sym.global.contains("_Z10botFromMidi"));
    assertFalse(sym.all.contains("_Z6unusedi"));
  }

  @Test
  public void testNativeLibraryDependentWithNDKPrior17() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_aar_native_deps/ndk_deps", tmp);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();
    AssumeAndroidPlatform.get(workspace).assumeArmIsAvailable();
    String target = "//:app-16";
    workspace.runBuckBuild(target).assertSuccess();

    Path aar =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem(),
                BuildTargetFactory.newInstance(target),
                AndroidAar.AAR_FORMAT));
    ZipInspector zipInspector = new ZipInspector(aar);
    zipInspector.assertFileExists("AndroidManifest.xml");
    zipInspector.assertFileExists("classes.jar");
    zipInspector.assertFileExists("R.txt");
    zipInspector.assertFileExists("res/");
    zipInspector.assertFileExists("assets/lib/armeabi/libfoo.so");
    zipInspector.assertFileExists("jni/armeabi/libbar.so");
    zipInspector.assertFileExists("assets/lib/armeabi-v7a/libfoo.so");
    zipInspector.assertFileExists("assets/lib/x86/libfoo.so");
    zipInspector.assertFileExists("jni/armeabi-v7a/libbar.so");
    zipInspector.assertFileExists("jni/x86/libbar.so");
  }

  @Test
  public void testEmptyExceptManifest() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmp);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();
    workspace.runBuckBuild("//apps/sample:nearly_empty_aar").assertSuccess();
  }

  @Test
  public void testResultIsRecorded() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmp);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();
    workspace.enableDirCache();
    workspace.runBuckBuild("//apps/sample:nearly_empty_aar").assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache");
    Path result = workspace.buildAndReturnOutput("//apps/sample:nearly_empty_aar");
    assertThat(workspace.asCell().getFilesystem().exists(result), Matchers.is(true));
  }
}
