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
import static junit.framework.Assert.assertNull;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ModuleToTargetsBinaryFileTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void testWriteAndGet_succeeds() throws Exception {
    File temporaryFile = temp.newFile("file.bin");
    ModuleToTargetsBinaryFile file = new ModuleToTargetsBinaryFile(temporaryFile.toPath());

    Map<String, Set<String>> data = new HashMap<>();
    data.put("mymodule", toSet("//foo", "//bar", "//baz"));
    data.put("anothermodule", toSet("//foobar"));

    file.write(data);

    assertEquals(toSet("//foo", "//bar", "//baz"), file.get("mymodule"));
    assertEquals(toSet("//foobar"), file.get("anothermodule"));
    assertNull(file.get("bogus"));
  }

  @Test
  public void testWriteAndGet_emptyMapSucceeds() throws Exception {
    File temporaryFile = temp.newFile("file2.bin");
    ModuleToTargetsBinaryFile file = new ModuleToTargetsBinaryFile(temporaryFile.toPath());

    Map<String, Set<String>> data = new HashMap<>();
    file.write(data);
    assertNull(file.get("foooooo"));
  }

  private static Set<String> toSet(String... strings) {
    return new HashSet<>(Arrays.asList(strings));
  }
}
