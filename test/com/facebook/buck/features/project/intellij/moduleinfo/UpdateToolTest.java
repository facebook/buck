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

package com.facebook.buck.features.project.intellij.moduleinfo;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class UpdateToolTest {
  private static final String IML_CONTENT =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<module type=\"PLUGIN_MODULE\" version=\"4\">\n"
          + "  <component name=\"DevKit.ModuleBuildProperties\" url=\"file://$MODULE_DIR$/../../resources/META-INF/plugin.xml\" />\n"
          + "  <component name=\"NewModuleRootManager\" inherit-compiler-output=\"true\">\n"
          + "    <exclude-output />\n"
          + "    <content url=\"file://$MODULE_DIR$/../..\">\n"
          + "      <sourceFolder url=\"file://$MODULE_DIR$/../..\" isTestSource=\"false\" />\n"
          + "      <sourceFolder url=\"file://$MODULE_DIR$/../../resources\" type=\"java-resource\" isTestSource=\"false\" />\n"
          + "      <excludeFolder url=\"file://$MODULE_DIR$/../../exclude\" isTestSource=\"false\" />\n"
          + "    </content>\n"
          + "    <orderEntry type=\"jdk\" jdkName=\"IDEA SDK\" jdkType=\"IDEA JDK\" />\n"
          + "    <orderEntry type=\"sourceFolder\" forTests=\"false\" />\n"
          + "    <orderEntry type=\"library\" scope=\"PROVIDED\" name=\"//foo:bar\" level=\"project\" />"
          + "    <orderEntry type=\"module\" scope=\"COMPILE\" module-name=\"foo_bar_1\" />"
          + "    <orderEntry type=\"module\" scope=\"COMPILE\" module-name=\"foo_bar_2\" />"
          + "  </component>\n"
          + "</module>";

  @Test
  public void readModuleInfoFromIml() throws IOException {
    TemporaryFolder projectFolder = new TemporaryFolder();
    projectFolder.create();
    Path projectRoot = projectFolder.getRoot().toPath();
    File moduleDir = projectFolder.newFolder(".idea", "modules");
    Path imlFile = moduleDir.toPath().resolve("foo.iml");
    Files.write(imlFile, IML_CONTENT.getBytes(StandardCharsets.UTF_8));
    ModuleInfo moduleInfo = UpdateTool.moduleInfoFromFile(projectRoot, imlFile);
    assertEquals(
        ModuleInfo.of(
            "foo",
            Paths.get(".idea/modules").toString(),
            ImmutableList.of("foo_bar_1", "foo_bar_2"),
            ImmutableList.of(
                ContentRootInfo.of(
                    "file://$MODULE_DIR$/../..",
                    ImmutableList.of(".", "/resources"),
                    ImmutableList.of("/exclude")))),
        moduleInfo);
  }
}
