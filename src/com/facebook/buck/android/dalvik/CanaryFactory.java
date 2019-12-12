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

import com.facebook.buck.jvm.java.classes.AbstractFileLike;
import com.facebook.buck.jvm.java.classes.FileLike;
import com.google.common.base.Charsets;
import com.google.common.primitives.Bytes;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Helper to create a "canary" class for the secondary DEX. See {@link #create}. */
public class CanaryFactory {
  static final String CANARY_PATH_FORMAT = "%s/dex%s/Canary";

  /**
   * Produced by:
   *
   * <pre>
   * $ echo -e "package secondary.dex01;\npublic interface Canary {}" > Canary.java
   * $ javac -target 6 -source 6 Canary.java
   * $ xxd -c 4 -g 1 Canary.class | cut -d' ' -f2-5 | sed -E 's/(..) ?/(byte) 0x\1, /g'
   * </pre>
   */
  private static final byte[] CANARY_START = {
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
    (byte) 0x61, (byte) 0x76, (byte) 0x61,
  };
  /*
    The part between CANARY_START and CANARY_REMAINDER is regenerated
    to rename the canary class.

    (byte) 0x01, (byte) 0x00, (byte) 0x16,
    (byte) 0x73, (byte) 0x65, (byte) 0x63, (byte) 0x6f,
    (byte) 0x6e, (byte) 0x64, (byte) 0x61, (byte) 0x72,
    (byte) 0x79, (byte) 0x2f, (byte) 0x64, (byte) 0x65,
    (byte) 0x78, (byte) 0x30, (byte) 0x31, (byte) 0x2f,
    (byte) 0x43, (byte) 0x61, (byte) 0x6e, (byte) 0x61,
    (byte) 0x72, (byte) 0x79,

    This corresponds to a constant pool entry of type CONSTANT_Utf8_info
    CONSTANT_Utf8_info {
        u1 tag;               0x01    (Utf8 string)
        u2 length;            0x0016  (22)
        u1 bytes[length];     secondary/dex01/Canary
    }

    u1,u2,etc. types in the java class file spec are big-endian

    https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.4
    https://en.wikipedia.org/wiki/Java_class_file
  */
  private static final byte[] CANARY_REMAINDER = {
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

  /** Utility class: do not instantiate */
  private CanaryFactory() {}

  /**
   * Adds a "canary" class to a secondary dex that can be safely loaded on any system. This avoids
   * an issue where, during secondary dex loading, we attempt to verify a secondary dex by loading
   * an arbitrary class, but the class we try to load isn't valid on that system (e.g., it depends
   * on Google Maps, but we are on AOSP).
   *
   * @param store dex store name of the current zip (to ensure unique names).
   * @param index Index of the current zip (to ensure unique names).
   */
  public static FileLike create(String store, String index) {
    String className = String.format(CANARY_PATH_FORMAT, store, index);
    byte[] classNameBytes = className.getBytes(Charsets.UTF_8);
    // See above comment regarding CONSTANT_Utf8_info
    byte[] stringInfo = new byte[3];
    stringInfo[0] = (byte) 0x01;
    stringInfo[1] = (byte) ((classNameBytes.length & 0x0000FF00) >> 8);
    stringInfo[2] = (byte) (classNameBytes.length & 0x000000FF);

    byte[] canaryClass = Bytes.concat(CANARY_START, stringInfo, classNameBytes, CANARY_REMAINDER);

    String classFilePath = className + ".class";
    return getCanaryClass(classFilePath, canaryClass);
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
