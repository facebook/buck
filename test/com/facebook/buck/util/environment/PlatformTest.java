/*
 * Copyright 2013-present Facebook, Inc.
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
package com.facebook.buck.util.environment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import org.junit.Test;

public class PlatformTest {
  /**
   * Check that Platform detects this OS. Note: if you're running on a reasonably mainstream OS and
   * this test fails, or if Buck's CI is failing on this, you should report a bug / add a test for
   * this in Platform. We shouldn't get {@code UNKNOWN} here.
   */
  @Test
  public void detectTest() {
    assertNotEquals(Platform.UNKNOWN, Platform.detect());
  }

  /**
   * Unix-like OSes only: try to get the "null" special filesystem entry. Almost certain to be
   * {@code /dev/null}. But let's verify our basic assumptions about the OS.
   */
  @Test
  public void nullFileTest() throws IOException {
    Platform platform = Platform.detect();
    assumeTrue(platform != Platform.UNKNOWN);

    File nullFile = platform.getNullDevicePath().toFile();

    if (platform.getType().isUnix()) {
      // Unix: `/dev/null`, though a device node, is a legit filesystem entry nonetheless.
      assertTrue(nullFile.exists());
    }

    // According to POSIX and Windows literature, the null file can be opened; all writes are
    // accepted (and their data discarded); and all reads result in EOF.  Try all of these, except
    // for writing.
    try (FileInputStream nullInputStream = new FileInputStream(nullFile)) {
      assertEquals(-1, nullInputStream.read());
    }
  }
}
