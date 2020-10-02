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

package com.facebook.buck.features.project.intellij.targetinfo;

import static junit.framework.Assert.assertEquals;

import com.facebook.buck.features.project.intellij.targetinfo.TargetInfo.BuckType;
import com.facebook.buck.features.project.intellij.targetinfo.TargetInfo.IntelliJType;
import com.facebook.buck.features.project.intellij.targetinfo.TargetInfo.ModuleLanguage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TargetInfoBinaryFileTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void testWriteAndGet_simpleTargetInfo() throws Exception {
    TargetInfo info = new TargetInfo();
    info.generatedSources = new ArrayList<>();
    info.generatedSources.add("/foo");
    info.intellijFilePath = "/foo/bar/foo.iml";
    info.intellijName = "foo";
    info.intellijType = IntelliJType.module;
    info.ruleType = BuckType.android_library;
    info.moduleLanguage = ModuleLanguage.SCALA;

    File temporaryFile = temp.newFile("file.bin");
    TargetInfoBinaryFile file = new TargetInfoBinaryFile(temporaryFile.toPath());
    Map<String, TargetInfo> map = new HashMap<>();
    map.put("//foo/bar:foo", info);
    file.write(map);

    TargetInfo newInfo = file.get("//foo/bar:foo");
    assertEquals(info, newInfo);
  }

  @Test
  public void testWriteAndGet_nulls() throws Exception {
    TargetInfo info = new TargetInfo();
    info.intellijFilePath = "/foo/bar/foo.iml";
    info.intellijName = "foo";

    File temporaryFile = temp.newFile("file.bin");
    TargetInfoBinaryFile file = new TargetInfoBinaryFile(temporaryFile.toPath());
    Map<String, TargetInfo> map = new HashMap<>();
    map.put("//foo/bar:foo", info);
    file.write(map);

    TargetInfo newInfo = file.get("//foo/bar:foo");
    assertEquals(info, newInfo);
  }

  @Test
  public void testWriteAndGet_emptyGeneratedSources() throws Exception {
    TargetInfo info = new TargetInfo();
    info.intellijFilePath = "/foo/bar/foo.iml";
    info.intellijName = "foo";
    info.generatedSources = new ArrayList<>();

    File temporaryFile = temp.newFile("file.bin");
    TargetInfoBinaryFile file = new TargetInfoBinaryFile(temporaryFile.toPath());
    Map<String, TargetInfo> map = new HashMap<>();
    map.put("//foo/bar:foo", info);
    file.write(map);

    TargetInfo newInfo = file.get("//foo/bar:foo");
    assertEquals(info, newInfo);
  }
}
