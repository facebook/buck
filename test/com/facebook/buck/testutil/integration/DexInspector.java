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

package com.facebook.buck.testutil.integration;

import static org.junit.Assert.assertThat;

import com.android.dex.Dex;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hamcrest.Matchers;

public class DexInspector {
  private final Dex dex;

  public DexInspector(Path apkFile, String path) throws IOException {
    try (FileSystem zipFile = FileSystems.newFileSystem(apkFile, null)) {
      Path dexFilePath = zipFile.getPath(path);
      try (InputStream inputStream = Files.newInputStream(dexFilePath)) {
        dex = new Dex(inputStream);
      }
    }
  }

  public DexInspector(Path apkFile) throws IOException {
    this(apkFile, "classes.dex");
  }

  public void assertTypeExists(String typeName) {
    assertThat(dex.typeNames(), Matchers.hasItem(typeName));
  }

  public void assertTypeDoesNotExist(String typeName) {
    assertThat(dex.typeNames(), Matchers.not(Matchers.hasItem(typeName)));
  }
}
