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

package com.facebook.buck.android.dalvik;

import com.facebook.buck.jvm.java.classes.AbstractFileLike;
import com.facebook.buck.jvm.java.classes.FileLike;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Helper to create a "canary" class for the secondary DEX. See {@link #create}. */
public class CanaryFactory {
  static final String CANARY_PATH_FORMAT = "%s/dex%s/Canary.class";

  /**
   * Produced by compiling the following Java file with JDK 7 with "-target 6 -source 6".
   *
   * <pre>
   * package secondary.dex000_000;
   * public interface Canary {}
   * </pre>
   *
   * To generate:
   *
   * <pre>
   * $ echo -e "package secondary.dex000_000;\npublic interface Canary {}" > Canary.java
   * $ javac -target 6 -source 6 Canary.java
   * $ xxd -c 4 -g 1 Canary.class | cut -d' ' -f2-5 | sed -E 's/(..) ?/(byte) 0x\1, /g'
   * </pre>
   */
  private static final byte[] CANARY_TEMPLATE = {
    (byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x32,
    (byte) 0x00, (byte) 0x07, (byte) 0x07, (byte) 0x00,
    (byte) 0x05, (byte) 0x07, (byte) 0x00, (byte) 0x06,
    // 16
    (byte) 0x01, (byte) 0x00, (byte) 0x0a, (byte) 0x53,
    (byte) 0x6f, (byte) 0x75, (byte) 0x72, (byte) 0x63,
    (byte) 0x65, (byte) 0x46, (byte) 0x69, (byte) 0x6c,
    (byte) 0x65, (byte) 0x01, (byte) 0x00, (byte) 0x0b,
    // 32
    (byte) 0x43, (byte) 0x61, (byte) 0x6e, (byte) 0x61,
    (byte) 0x72, (byte) 0x79, (byte) 0x2e, (byte) 0x6a,
    (byte) 0x61, (byte) 0x76, (byte) 0x61, (byte) 0x01,
    (byte) 0x00, (byte) 0x1b,
    // secondary/dex (Offset = 46)
    (byte) 0x73, (byte) 0x65, (byte) 0x63, (byte) 0x6f,
    (byte) 0x6e, (byte) 0x64, (byte) 0x61, (byte) 0x72,
    (byte) 0x79, (byte) 0x2f, (byte) 0x64, (byte) 0x65,
    (byte) 0x78,
    // 000_000
    (byte) 0x30, (byte) 0x30, (byte) 0x30, (byte) 0x5f,
    (byte) 0x30, (byte) 0x30, (byte) 0x30,
    // ...
    (byte) 0x2f, (byte) 0x43, (byte) 0x61, (byte) 0x6e,
    (byte) 0x61, (byte) 0x72, (byte) 0x79, (byte) 0x01,
    (byte) 0x00, (byte) 0x10, (byte) 0x6a, (byte) 0x61,
    (byte) 0x76, (byte) 0x61, (byte) 0x2f, (byte) 0x6c,
    (byte) 0x61, (byte) 0x6e, (byte) 0x67, (byte) 0x2f,
    (byte) 0x4f, (byte) 0x62, (byte) 0x6a, (byte) 0x65,
    (byte) 0x63, (byte) 0x74, (byte) 0x06, (byte) 0x01,
    (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x02,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,
    (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x00,
    (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x04,
  };

  private static final int CANARY_STORE_OFFSET = 46;

  /**
   * Offset into {@link #CANARY_TEMPLATE} where we find the 7-byte UTF-8 index that must be updated
   * for each canary class to change the package.
   */
  private static final int CANARY_INDEX_OFFSET =
      CANARY_STORE_OFFSET + "secondary/dex".getBytes(Charsets.UTF_8).length;

  /** Utility class: do not instantiate */
  private CanaryFactory() {}

  /**
   * Adds a "canary" class to a secondary dex that can be safely loaded on any system. This avoids
   * an issue where, during secondary dex loading, we attempt to verify a secondary dex by loading
   * an arbitrary class, but the class we try to load isn't valid on that system (e.g., it depends
   * on Google Maps, but we are on AOSP).
   *
   * @param store dex store name of the current zip (to ensure unique names).
   * @param index Index of the current zip (to ensure unique names), must be 7 UTF-8 bytes long
   */
  public static FileLike create(String store, String index) {
    byte[] canaryClass = Arrays.copyOf(CANARY_TEMPLATE, CANARY_TEMPLATE.length);
    byte[] canaryIndexBytes = index.getBytes(Charsets.UTF_8);
    Preconditions.checkState(
        canaryIndexBytes.length == 7,
        "Formatted index string \"" + index + "\" is not exactly 7 bytes.");
    System.arraycopy(canaryIndexBytes, 0, canaryClass, CANARY_INDEX_OFFSET, 7);
    byte[] canaryStoreBytes = store.getBytes(Charsets.UTF_8);
    Preconditions.checkState(
        canaryStoreBytes.length == 9,
        "Formatted store string \"" + store + "\" is not exactly 9 bytes.\"");
    System.arraycopy(canaryStoreBytes, 0, canaryClass, CANARY_STORE_OFFSET, 9);
    String relativePath = String.format(CANARY_PATH_FORMAT, store, index);
    return getCanaryClass(relativePath, canaryClass);
  }

  private static FileLike getCanaryClass(String relativePath, byte[] canaryClass) {
    return new AbstractFileLike() {
      @Override
      public Path getContainer() {
        return Paths.get(":memory:");
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
