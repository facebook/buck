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

package com.facebook.buck.android;

import com.facebook.buck.android.apkmodule.APKModule;
import java.nio.file.Path;
import java.util.Optional;

/** Specifies how secondary .dex files should be stored in the .apk. */
enum DexStore {
  /** Secondary dexes should be raw dex files for inclusion in the APK. */
  RAW {
    @Override
    public String prefix() {
      return "classes";
    }

    @Override
    public String suffix() {
      return ".dex";
    }

    // Google expects secondary dex files to start at 2.
    // classes.dex is number 1.
    // voltron modules start with index 1
    @Override
    public int secondaryDexIndexOffset(int index, APKModule apkModule) {
      if (apkModule.isRootModule()) {
        return index + 2;
      }
      return index + 1;
    }

    @Override
    public boolean matchesPath(Path path) {
      return path.getFileName().toString().matches(".*\\d+\\.dex");
    }

    @Override
    String formatDexFileName(String prefix, String index) {
      if ("1".equals(index)) {
        return String.format("%s%s", prefix, suffix());
      }
      return String.format("%s%s%s", prefix, index, suffix());
    }
  },

  /** Secondary dexes should be compressed using JAR's deflate. */
  JAR {
    @Override
    public String suffix() {
      return ".dex.jar";
    }
  },

  /** Secondary dexes should be stored uncompressed in jars that will be XZ-compressed. */
  XZ {
    @Override
    public String suffix() {
      return ".dex.jar.xz";
    }
  },

  /** Secondary dexes will be solid compressed into a single XZS file. */
  XZS {
    // Since secondary dexes are created in parallel, we add a .tmp~
    // extension to indicate that this process is not yet complete.
    // A .dex.jar.xzs.tmp~ is a dex file that is waiting to be concatenated
    // and then XZ compressed. The ~ character is a hack to ensure that
    // the external apkbuilder tool does not copy this file to the final apk.
    // The alternative would require either rewriting apkbuilder or writing substantial code
    // to get around the current limitations of its API.
    @Override
    public String suffix() {
      return ".dex.jar.xzs.tmp~";
    }
  },
  ;

  public String prefix() {
    return "secondary";
  }

  public abstract String suffix();

  // Start at one for easier comprehension by humans.
  @SuppressWarnings("unused")
  public int secondaryDexIndexOffset(int index, APKModule apkModule) {
    return index + 1;
  }

  // Canary class index must have at least two digits.
  String getCanaryClassIndexName(Optional<Integer> groupIndex, int index, APKModule apkModule) {
    return secondaryDexIndexName(groupIndex, index, "%02d", apkModule);
  }

  private String getSecondaryDexFileNameIndex(
      Optional<Integer> groupIndex, int index, APKModule apkModule) {
    return secondaryDexIndexName(groupIndex, index, "%d", apkModule);
  }

  private String secondaryDexIndexName(
      Optional<Integer> groupIndex, int index, String singleIndexFormat, APKModule apkModule) {
    if (groupIndex.isPresent()) {
      return String.format("%d_%d", groupIndex.get(), secondaryDexIndexOffset(index, apkModule));
    } else {
      return String.format(singleIndexFormat, secondaryDexIndexOffset(index, apkModule));
    }
  }

  /**
   * @param module The apk module the file belongs to
   * @param index The index of a given secondary dex file, starting from 0.
   * @return The appropriate name for the dex file at {@code index}.
   */
  public String fileNameForSecondary(APKModule module, int index) {
    return fileNameForSecondary(module, Optional.empty(), index);
  }

  public String fileNameForSecondary(APKModule module, Optional<Integer> groupIndex, int index) {
    String fileNameIndex = getSecondaryDexFileNameIndex(groupIndex, index, module);
    return formatDexFileName(prefix(), fileNameIndex);
  }

  String formatDexFileName(String prefix, String fileNameIndex) {
    return String.format("%s-%s%s", prefix, fileNameIndex, suffix());
  }

  /**
   * @param path The path where a secondary dex file will be written.
   * @return Whether that file is of this DexStore type.
   */
  public boolean matchesPath(Path path) {
    return path.getFileName().toString().endsWith(suffix());
  }
}
