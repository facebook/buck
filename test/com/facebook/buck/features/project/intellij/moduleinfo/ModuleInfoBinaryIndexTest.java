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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.util.List;
import junit.framework.TestCase;
import org.junit.rules.TemporaryFolder;

public class ModuleInfoBinaryIndexTest extends TestCase {
  private TemporaryFolder temp;

  @Override
  protected void setUp() throws Exception {
    temp = new TemporaryFolder();
    temp.create();
  }

  public void testWriteAndRead() throws IOException {
    ModuleInfoBinaryIndex index = new ModuleInfoBinaryIndex(temp.getRoot().toPath());
    List<ModuleInfo> moduleInfos = createModuleInfos();
    index.write(moduleInfos);

    assertEquals(sort(moduleInfos), sort(index.read()));
  }

  public void testUpdateAndRead() throws IOException {
    ModuleInfoBinaryIndex index = new ModuleInfoBinaryIndex(temp.getRoot().toPath());
    List<ModuleInfo> moduleInfos = createModuleInfos();
    index.write(moduleInfos);

    List<ModuleInfo> updatedModuleInfos = createUpdatedModuleInfos();
    index.update(updatedModuleInfos);

    List<ModuleInfo> merged =
        ModuleInfoBinaryIndex.mergeModuleInfos(moduleInfos, updatedModuleInfos);

    assertEquals(sort(createMergedModuleInfos()), sort(merged));
    assertEquals(sort(merged), sort(index.read()));
  }

  private static List<ModuleInfo> sort(List<ModuleInfo> list) {
    return ImmutableList.sortedCopyOf(
        Ordering.natural().onResultOf(ModuleInfo::getModuleName), list);
  }

  private static List<ModuleInfo> createModuleInfos() {
    return ImmutableList.of(
        createModuleInfo("node00", ImmutableList.of("node10", "node11")),
        createModuleInfo("node10", ImmutableList.of("node20", "node21")),
        createModuleInfo("node11", ImmutableList.of()),
        createModuleInfo("node20", ImmutableList.of("node30")),
        createModuleInfo("node21", ImmutableList.of()),
        createModuleInfo("node30", ImmutableList.of()));
  }

  private static List<ModuleInfo> createUpdatedModuleInfos() {
    return ImmutableList.of(
        createModuleInfo("node10", ImmutableList.of("node20", "node22")),
        createModuleInfo("node20", ImmutableList.of("node30", "node31")),
        createModuleInfo("node22", ImmutableList.of()),
        createModuleInfo("node30", ImmutableList.of()),
        createModuleInfo("node31", ImmutableList.of()));
  }

  private static List<ModuleInfo> createMergedModuleInfos() {
    return ImmutableList.of(
        createModuleInfo("node00", ImmutableList.of("node10", "node11")),
        createModuleInfo("node10", ImmutableList.of("node20", "node22")),
        createModuleInfo("node11", ImmutableList.of()),
        createModuleInfo("node20", ImmutableList.of("node30", "node31")),
        createModuleInfo("node21", ImmutableList.of()),
        createModuleInfo("node30", ImmutableList.of()),
        createModuleInfo("node31", ImmutableList.of()),
        createModuleInfo("node22", ImmutableList.of()));
  }

  private static ModuleInfo createModuleInfo(String moduleName, List<String> deps) {
    String moduleDir = "/path/to/" + moduleName;
    return ModuleInfo.of(
        moduleName,
        moduleDir,
        deps,
        ImmutableList.of(
            ContentRootInfo.of(moduleDir, ImmutableList.of("."), ImmutableList.of("/exclulded"))));
  }
}
