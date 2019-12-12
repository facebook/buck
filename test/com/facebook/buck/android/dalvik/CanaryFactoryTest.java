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

package com.facebook.buck.android.dalvik;

import static org.junit.Assert.assertArrayEquals;

import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

public class CanaryFactoryTest {
  /**
   * Produced by:
   *
   * <pre>
   * $ echo -e "package secondary.dex01;\npublic interface Canary {}" > Canary.java
   * $ javac -target 6 -source 6 Canary.java
   * $ xxd -c 4 -g 1 Canary.class | cut -d' ' -f2-5 | sed -E 's/(..) ?/(byte) 0x\1, /g'
   * </pre>
   */
  private static final byte[] SECONDARY_DEX01_CANARY = {
    (byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x32,
    (byte) 0x00, (byte) 0x07, (byte) 0x07, (byte) 0x00,
    (byte) 0x05, (byte) 0x07, (byte) 0x00, (byte) 0x06,
    (byte) 0x01, (byte) 0x00, (byte) 0x0a, (byte) 0x53,
    (byte) 0x6f, (byte) 0x75, (byte) 0x72, (byte) 0x63,
    (byte) 0x65, (byte) 0x46, (byte) 0x69, (byte) 0x6c,
    (byte) 0x65, (byte) 0x01, (byte) 0x00, (byte) 0x0b,
    (byte) 0x43, (byte) 0x61, (byte) 0x6e, (byte) 0x61,
    (byte) 0x72, (byte) 0x79, (byte) 0x2e, (byte) 0x6a,
    (byte) 0x61, (byte) 0x76, (byte) 0x61, (byte) 0x01,
    (byte) 0x00, (byte) 0x16, (byte) 0x73, (byte) 0x65,
    (byte) 0x63, (byte) 0x6f, (byte) 0x6e, (byte) 0x64,
    (byte) 0x61, (byte) 0x72, (byte) 0x79, (byte) 0x2f,
    (byte) 0x64, (byte) 0x65, (byte) 0x78, (byte) 0x30,
    (byte) 0x31, (byte) 0x2f, (byte) 0x43, (byte) 0x61,
    (byte) 0x6e, (byte) 0x61, (byte) 0x72, (byte) 0x79,
    (byte) 0x01, (byte) 0x00, (byte) 0x10, (byte) 0x6a,
    (byte) 0x61, (byte) 0x76, (byte) 0x61, (byte) 0x2f,
    (byte) 0x6c, (byte) 0x61, (byte) 0x6e, (byte) 0x67,
    (byte) 0x2f, (byte) 0x4f, (byte) 0x62, (byte) 0x6a,
    (byte) 0x65, (byte) 0x63, (byte) 0x74, (byte) 0x06,
    (byte) 0x01, (byte) 0x00, (byte) 0x01, (byte) 0x00,
    (byte) 0x02, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x01, (byte) 0x00, (byte) 0x03, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x02, (byte) 0x00,
    (byte) 0x04,
  };
  /**
   * Produced by:
   *
   * <pre>
   * $ echo -e "package module.dex001_001;\npublic interface Canary {}" > Canary.java
   * $ javac -target 6 -source 6 Canary.java
   * $ xxd -c 4 -g 1 Canary.class | cut -d' ' -f2-5 | sed -E 's/(..) ?/(byte) 0x\1, /g'
   * </pre>
   */
  private static final byte[] MODULE_DEX001_001_CANARY = {
    (byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x32,
    (byte) 0x00, (byte) 0x07, (byte) 0x07, (byte) 0x00,
    (byte) 0x05, (byte) 0x07, (byte) 0x00, (byte) 0x06,
    (byte) 0x01, (byte) 0x00, (byte) 0x0a, (byte) 0x53,
    (byte) 0x6f, (byte) 0x75, (byte) 0x72, (byte) 0x63,
    (byte) 0x65, (byte) 0x46, (byte) 0x69, (byte) 0x6c,
    (byte) 0x65, (byte) 0x01, (byte) 0x00, (byte) 0x0b,
    (byte) 0x43, (byte) 0x61, (byte) 0x6e, (byte) 0x61,
    (byte) 0x72, (byte) 0x79, (byte) 0x2e, (byte) 0x6a,
    (byte) 0x61, (byte) 0x76, (byte) 0x61, (byte) 0x01,
    (byte) 0x00, (byte) 0x18, (byte) 0x6d, (byte) 0x6f,
    (byte) 0x64, (byte) 0x75, (byte) 0x6c, (byte) 0x65,
    (byte) 0x2f, (byte) 0x64, (byte) 0x65, (byte) 0x78,
    (byte) 0x30, (byte) 0x30, (byte) 0x31, (byte) 0x5f,
    (byte) 0x30, (byte) 0x30, (byte) 0x31, (byte) 0x2f,
    (byte) 0x43, (byte) 0x61, (byte) 0x6e, (byte) 0x61,
    (byte) 0x72, (byte) 0x79, (byte) 0x01, (byte) 0x00,
    (byte) 0x10, (byte) 0x6a, (byte) 0x61, (byte) 0x76,
    (byte) 0x61, (byte) 0x2f, (byte) 0x6c, (byte) 0x61,
    (byte) 0x6e, (byte) 0x67, (byte) 0x2f, (byte) 0x4f,
    (byte) 0x62, (byte) 0x6a, (byte) 0x65, (byte) 0x63,
    (byte) 0x74, (byte) 0x06, (byte) 0x01, (byte) 0x00,
    (byte) 0x01, (byte) 0x00, (byte) 0x02, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00,
    (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x02, (byte) 0x00, (byte) 0x04,
  };

  @Test
  public void testSecondary01() throws IOException {
    InputStream canaryInputStream = CanaryFactory.create("secondary", "01").getInput();
    byte[] generatedBytes = ByteStreams.toByteArray(canaryInputStream);

    assertArrayEquals(SECONDARY_DEX01_CANARY, generatedBytes);
  }

  @Test
  public void testModule001_001() throws IOException {
    InputStream canaryInputStream = CanaryFactory.create("module", "001_001").getInput();
    byte[] generatedBytes = ByteStreams.toByteArray(canaryInputStream);

    assertArrayEquals(MODULE_DEX001_001_CANARY, generatedBytes);
  }
}
