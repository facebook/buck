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

package com.facebook.buck.dalvik;

import com.facebook.buck.java.classes.AbstractFileLike;
import com.facebook.buck.java.classes.FileLike;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Helper to create a "canary" class for the secondary DEX. See {@link #create}.
 */
public class CanaryFactory {

  private static final String CANARY_PATH_FORMAT = "secondary/dex%02d/Canary.class";

  /**
   * Produced by compiling the following Java file with JDK 7 with "-target 6 -source 6".
   * <pre>
   * package secondary.dex01;
   * public interface Canary {}
   * </pre>
   */
  private static final byte[] CANARY_TEMPLATE = {
      -54,   -2,  -70,  -66, 0x00, 0x00, 0x00, 0x32,
      0x00, 0x05, 0x07, 0x00, 0x03, 0x07, 0x00, 0x04,
      0x01, 0x00, 0x16, 0x73, 0x65, 0x63, 0x6f, 0x6e,
      0x64, 0x61, 0x72, 0x79, 0x2f, 0x64, 0x65, 0x78,
      0x30, 0x31, 0x2f, 0x43, 0x61, 0x6e, 0x61, 0x72,
      0x79, 0x01, 0x00, 0x10, 0x6a, 0x61, 0x76, 0x61,
      0x2f, 0x6c, 0x61, 0x6e, 0x67, 0x2f, 0x4f, 0x62,
      0x6a, 0x65, 0x63, 0x74, 0x06, 0x01, 0x00, 0x01,
      0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
      0x00, 0x00
  };

  /**
   * Offset into {@link #CANARY_TEMPLATE} where we find the 2-byte UTF-8 index that must be
   * updated for each canary class to change the package.
   */
  private static final int CANARY_INDEX_OFFSET = 32;

  /**
   * Adds a "canary" class to a secondary dex that can be safely loaded on any system.
   * This avoids an issue where, during secondary dex loading, we attempt to verify a
   * secondary dex by loading an arbitrary class, but the class we try to load isn't
   * valid on that system (e.g., it depends on Google Maps, but we are on AOSP).
   *
   * @param index Index of the current zip (to ensure unique names).
   */
  public static FileLike create(final int index) {
    final byte[] canaryClass = Arrays.copyOf(CANARY_TEMPLATE, CANARY_TEMPLATE.length);
    final String canaryIndexStr = String.format("%02d", index);
    byte[] canaryIndexBytes = canaryIndexStr.getBytes(Charsets.UTF_8);
    Preconditions.checkState(canaryIndexBytes.length == 2,
        "Formatted index string should always be 2 bytes.");
    System.arraycopy(canaryIndexBytes, 0, canaryClass, CANARY_INDEX_OFFSET, 2);
    String relativePath = String.format(CANARY_PATH_FORMAT, index);
    return getCanaryClass(relativePath, canaryClass);
  }

  private static FileLike getCanaryClass(final String relativePath, final byte[] canaryClass) {
    return new AbstractFileLike() {
      @Override
      public File getContainer() {
        return new File(":memory:");
      }

      @Override
      public String getRelativePath() {
        return relativePath;
      }

      @Override
      public long getSize() {
        return canaryClass.length;
      }

      @Override
      public InputStream getInput() {
        return new ByteArrayInputStream(canaryClass);
      }
    };
  }
}
