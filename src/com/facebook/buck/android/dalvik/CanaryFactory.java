/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import com.facebook.buck.android.dex.CanaryUtils;
import com.facebook.buck.jvm.java.classes.AbstractFileLike;
import com.facebook.buck.jvm.java.classes.FileLike;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Helper to create a "canary" class for the secondary DEX. See {@link #create}. */
public class CanaryFactory {
  static final String CANARY_PATH_FORMAT = "%s/dex%s/Canary";

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
    byte[] canaryClass = CanaryUtils.createCanaryClassByteCode(className);

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
