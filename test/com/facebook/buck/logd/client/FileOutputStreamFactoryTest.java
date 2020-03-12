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

package com.facebook.buck.logd.client;

import static org.junit.Assert.*;

import com.facebook.buck.logd.proto.LogType;
import com.google.common.base.Charsets;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileOutputStreamFactoryTest {
  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void createLogStream() throws IOException {
    String message = "hello world";
    LogStreamFactory logStreamFactory = new FileOutputStreamFactory();
    try (OutputStream fileOutputStream =
        logStreamFactory.createLogStream(getTestFilePath(), LogType.BUCK_LOG)) {
      fileOutputStream.write(message.getBytes(Charsets.UTF_8));
    }

    assertEquals(message, readFile(getTestFilePath()));
  }

  private String readFile(String filePath) throws IOException {
    return new String(Files.readAllBytes(Paths.get(filePath)));
  }

  private String getTestFilePath() {
    return tempFolder.getRoot().getAbsolutePath() + "/logs/buck.log";
  }
}
