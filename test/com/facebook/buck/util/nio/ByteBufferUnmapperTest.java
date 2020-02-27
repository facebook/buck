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

package com.facebook.buck.util.nio;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.TemporaryPaths;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.Rule;
import org.junit.Test;

public class ByteBufferUnmapperTest {
  public @Rule TemporaryPaths temporaryDir = new TemporaryPaths();

  @Test
  public void testByteBufferUnmapper() throws IOException {
    Path tempFile = temporaryDir.getRoot().resolve("newFile");
    Files.write(tempFile, new byte[] {10, 20, 30, 40});
    try (FileChannel channel =
        FileChannel.open(tempFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      try (ByteBufferUnmapper unmapper =
          ByteBufferUnmapper.createUnsafe(
              channel.map(FileChannel.MapMode.READ_WRITE, 0, channel.size()))) {
        assertEquals(4, unmapper.getByteBuffer().remaining());
      }
    }
    Files.write(tempFile, new byte[] {42, 31, 22});
  }
}
