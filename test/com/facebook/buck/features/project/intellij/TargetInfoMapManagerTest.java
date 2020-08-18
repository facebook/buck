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

package com.facebook.buck.features.project.intellij;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.features.project.intellij.lang.android.AndroidManifestParser;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.DefaultJavaPackageFinder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Collections;
import org.junit.Test;

public class TargetInfoMapManagerTest {
  @Test
  public void testTargetInfoMapForModuleGraph1() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    String targetInfo = writeTargetInfoMapForModuleGraph1(filesystem);
    assertEquals(
        toJson(
            ImmutableSortedMap.of(
                "//java/com/example/base:base",
                ImmutableSortedMap.of(
                    TargetInfoMapManager.BUCK_TYPE,
                    "java_library",
                    TargetInfoMapManager.INTELLIJ_FILE_PATH,
                    filesystem.getPath("java/com/example/base/java_com_example_base.iml"),
                    TargetInfoMapManager.INTELLIJ_NAME,
                    "java_com_example_base",
                    TargetInfoMapManager.INTELLIJ_TYPE,
                    TargetInfoMapManager.MODULE_TYPE,
                    TargetInfoMapManager.GENERATED_SOURCES,
                    Collections.singletonList(filesystem.getPath("buck-out/annotation/base"))),
                "//third_party/guava:guava",
                ImmutableSortedMap.of(
                    TargetInfoMapManager.BUCK_TYPE,
                    "java_library",
                    TargetInfoMapManager.INTELLIJ_FILE_PATH,
                    filesystem.getPath("third_party/guava/third_party_guava.iml"),
                    TargetInfoMapManager.INTELLIJ_NAME,
                    "third_party_guava",
                    TargetInfoMapManager.INTELLIJ_TYPE,
                    TargetInfoMapManager.MODULE_TYPE))),
        targetInfo);
  }

  @Test
  public void testTargetInfoMapForModuleGraph2() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    String targetInfo = writeTargetInfoMapForModuleGraph2(filesystem, false);
    assertEquals(
        toJson(
            ImmutableSortedMap.of(
                "//java/com/example/base2:base2",
                ImmutableSortedMap.of(
                    TargetInfoMapManager.BUCK_TYPE,
                    "java_library",
                    TargetInfoMapManager.INTELLIJ_FILE_PATH,
                    filesystem.getPath("java/com/example/base2/java_com_example_base2.iml"),
                    TargetInfoMapManager.INTELLIJ_NAME,
                    "java_com_example_base2",
                    TargetInfoMapManager.INTELLIJ_TYPE,
                    TargetInfoMapManager.MODULE_TYPE),
                "//java/com/example/base:base",
                ImmutableSortedMap.of(
                    TargetInfoMapManager.BUCK_TYPE,
                    "java_library",
                    TargetInfoMapManager.INTELLIJ_FILE_PATH,
                    filesystem.getPath("java/com/example/base/java_com_example_base.iml"),
                    TargetInfoMapManager.INTELLIJ_NAME,
                    "java_com_example_base",
                    TargetInfoMapManager.INTELLIJ_TYPE,
                    TargetInfoMapManager.MODULE_TYPE))),
        targetInfo);
  }

  @Test
  public void testTargetInfoWithJvmLanguage() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    String targetInfo =
        writeTargetInfoMapForModuleGraphWithGivenJvmLanguage(
            filesystem, AndroidLibraryDescription.JvmLanguage.KOTLIN);
    assertEquals(
        toJson(
            ImmutableSortedMap.of(
                "//java/com/example/base2:base2",
                ImmutableSortedMap.of(
                    TargetInfoMapManager.BUCK_TYPE,
                    "android_library",
                    TargetInfoMapManager.INTELLIJ_FILE_PATH,
                    filesystem.getPath("java/com/example/base2/java_com_example_base2.iml"),
                    TargetInfoMapManager.INTELLIJ_NAME,
                    "java_com_example_base2",
                    TargetInfoMapManager.INTELLIJ_TYPE,
                    TargetInfoMapManager.MODULE_TYPE),
                "//java/com/example/base:base",
                ImmutableSortedMap.of(
                    TargetInfoMapManager.BUCK_TYPE,
                    "android_library",
                    TargetInfoMapManager.INTELLIJ_FILE_PATH,
                    filesystem.getPath("java/com/example/base/java_com_example_base.iml"),
                    TargetInfoMapManager.INTELLIJ_NAME,
                    "java_com_example_base",
                    TargetInfoMapManager.INTELLIJ_TYPE,
                    TargetInfoMapManager.MODULE_TYPE,
                    TargetInfoMapManager.MODULE_LANG,
                    "KOTLIN"))),
        targetInfo);
  }

  @Test
  public void testTargetInfoMapUpdate() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    String targetInfo = writeTargetInfoMapForModuleGraph1(filesystem);
    assertEquals(
        toJson(
            ImmutableSortedMap.of(
                "//java/com/example/base:base",
                ImmutableSortedMap.of(
                    TargetInfoMapManager.BUCK_TYPE,
                    "java_library",
                    TargetInfoMapManager.INTELLIJ_FILE_PATH,
                    filesystem.getPath("java/com/example/base/java_com_example_base.iml"),
                    TargetInfoMapManager.INTELLIJ_NAME,
                    "java_com_example_base",
                    TargetInfoMapManager.INTELLIJ_TYPE,
                    TargetInfoMapManager.MODULE_TYPE,
                    TargetInfoMapManager.GENERATED_SOURCES,
                    Collections.singletonList(filesystem.getPath("buck-out/annotation/base"))),
                "//third_party/guava:guava",
                ImmutableSortedMap.of(
                    TargetInfoMapManager.BUCK_TYPE,
                    "java_library",
                    TargetInfoMapManager.INTELLIJ_FILE_PATH,
                    filesystem.getPath("third_party/guava/third_party_guava.iml"),
                    TargetInfoMapManager.INTELLIJ_NAME,
                    "third_party_guava",
                    TargetInfoMapManager.INTELLIJ_TYPE,
                    TargetInfoMapManager.MODULE_TYPE))),
        targetInfo);

    targetInfo = writeTargetInfoMapForModuleGraph2(filesystem, true);
    assertEquals(
        toJson(
            ImmutableSortedMap.of(
                "//java/com/example/base2:base2",
                ImmutableSortedMap.of(
                    TargetInfoMapManager.BUCK_TYPE,
                    "java_library",
                    TargetInfoMapManager.INTELLIJ_FILE_PATH,
                    filesystem.getPath("java/com/example/base2/java_com_example_base2.iml"),
                    TargetInfoMapManager.INTELLIJ_NAME,
                    "java_com_example_base2",
                    TargetInfoMapManager.INTELLIJ_TYPE,
                    TargetInfoMapManager.MODULE_TYPE),
                "//java/com/example/base:base",
                ImmutableSortedMap.of(
                    TargetInfoMapManager.BUCK_TYPE,
                    "java_library",
                    TargetInfoMapManager.INTELLIJ_FILE_PATH,
                    filesystem.getPath("java/com/example/base/java_com_example_base.iml"),
                    TargetInfoMapManager.INTELLIJ_NAME,
                    "java_com_example_base",
                    TargetInfoMapManager.INTELLIJ_TYPE,
                    TargetInfoMapManager.MODULE_TYPE),
                "//third_party/guava:guava",
                ImmutableSortedMap.of(
                    TargetInfoMapManager.BUCK_TYPE,
                    "java_library",
                    TargetInfoMapManager.INTELLIJ_FILE_PATH,
                    filesystem.getPath("third_party/guava/third_party_guava.iml"),
                    TargetInfoMapManager.INTELLIJ_NAME,
                    "third_party_guava",
                    TargetInfoMapManager.INTELLIJ_TYPE,
                    TargetInfoMapManager.MODULE_TYPE))),
        targetInfo);
  }

  private String writeTargetInfoMapForModuleGraph1(ProjectFilesystem filesystem)
      throws IOException {
    TargetNode<?> guavaTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//third_party/guava:guava"))
            .addSrc(Paths.get("third_party/guava/src/Collections.java"))
            .build();

    TargetNode<?> baseTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addDep(guavaTargetNode.getBuildTarget())
            .addSrc(Paths.get("java/com/example/base/Base.java"))
            .addAnnotationProcessors("//annotation:processor")
            .build();

    return createTargetInfoMapManagerAndWrite(
        filesystem, ImmutableSet.of(guavaTargetNode, baseTargetNode), false);
  }

  private String writeTargetInfoMapForModuleGraph2(ProjectFilesystem filesystem, boolean isUpdate)
      throws IOException {
    TargetNode<?> baseTargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addSrc(Paths.get("java/com/example/base/BaseChanged.java"))
            .build();

    TargetNode<?> base2TargetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base2:base2"))
            .addSrc(Paths.get("java/com/example/base/Base.java"))
            .build();

    return createTargetInfoMapManagerAndWrite(
        filesystem, ImmutableSet.of(baseTargetNode, base2TargetNode), isUpdate);
  }

  private String writeTargetInfoMapForModuleGraphWithGivenJvmLanguage(
      ProjectFilesystem filesystem, AndroidLibraryDescription.JvmLanguage jvmLanguage)
      throws IOException {
    TargetNode<?> baseTargetNode =
        AndroidLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base:base"))
            .addSrc(Paths.get("java/com/example/android/AndroidLib.java"))
            .setLanguage(jvmLanguage)
            .build();

    TargetNode<?> base2TargetNode =
        AndroidLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/example/base2:base2"))
            .addSrc(Paths.get("java/com/example/base/Base.java"))
            .build();

    return createTargetInfoMapManagerAndWrite(
        filesystem, ImmutableSet.of(baseTargetNode, base2TargetNode), false);
  }

  private String createTargetInfoMapManagerAndWrite(
      ProjectFilesystem filesystem, ImmutableSet<TargetNode<?>> targetNodes, boolean isUpdate)
      throws IOException {
    IjProjectTemplateDataPreparer dataPreparer =
        dataPreparer(filesystem, IjModuleGraphTest.createModuleGraph(targetNodes));
    IjProjectConfig projectConfig = projectConfig();
    IJProjectCleaner cleaner = new IJProjectCleaner(filesystem);
    TargetInfoMapManager targetInfoMapManager =
        new TargetInfoMapManager(
            TargetGraphFactory.newInstance(targetNodes), projectConfig, filesystem, isUpdate);
    targetInfoMapManager.write(
        dataPreparer.getModulesToBeWritten(),
        dataPreparer.getAllLibraries(),
        new BuckOutPathConverter(projectConfig),
        cleaner);
    return targetInfoMapManager.readTargetInfoMapAsString();
  }

  private IjProjectConfig projectConfig() {
    return IjTestProjectConfig.createBuilder(
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "intellij", ImmutableMap.of("generate_target_info_map", "true")))
                .build())
        .build();
  }

  private String toJson(ImmutableSortedMap<String, ImmutableSortedMap<String, Object>> map)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (JsonGenerator generator = ObjectMappers.createGenerator(out).useDefaultPrettyPrinter()) {
      generator.writeObject(map);
    }
    return out.toString();
  }

  private IjProjectTemplateDataPreparer dataPreparer(
      ProjectFilesystem filesystem, IjModuleGraph moduleGraph) {
    JavaPackageFinder javaPackageFinder =
        DefaultJavaPackageFinder.createDefaultJavaPackageFinder(filesystem, ImmutableSet.of());
    AndroidManifestParser androidManifestParser = new AndroidManifestParser(filesystem);
    return new IjProjectTemplateDataPreparer(
        javaPackageFinder, moduleGraph, filesystem, projectConfig(), androidManifestParser);
  }
}
