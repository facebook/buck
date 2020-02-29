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
    // I guess classes.dex is number 1.
    @Override
    public String index(int index) {
      return String.format("%d", index + 2);
    }

    @Override
    public boolean matchesPath(Path path) {
      return path.getFileName().toString().matches(".*\\d+\\.dex");
    }

    @Override
    String fileNameForSecondary(String prefix, String index) {
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
  public String index(int index) {
    return String.format("%d", index + 1);
  }

  public String index(Optional<Integer> groupIndex, int index) {
    if (groupIndex.isPresent()) {
      return String.format("%d_%s", groupIndex.get(), index(index));
    } else {
      return index(index);
    }
  }

  /**
   * @param module The apk module the file belongs to
   * @param index The index of a given secondary dex file, starting from 0.
   * @return The appropriate name for the dex file at {@code index}.
   */
  public String fileNameForSecondary(APKModule module, int index) {
    return fileNameForSecondary(module, index(index));
  }

  /**
   * @param module The apk module the file belongs to
   * @param index The index of a given secondary dex file
   * @return The appropriate name for the dex file at {@code index}.
   */
  public String fileNameForSecondary(APKModule module, String index) {
    String prefix = module.isRootModule() ? prefix() : module.getName();
    return fileNameForSecondary(prefix, index);
  }

  String fileNameForSecondary(String prefix, String index) {
    return String.format("%s-%s%s", prefix, index, suffix());
  }

  /**
   * @param path The path where a secondary dex file will be written.
   * @return Whether that file is of this DexStore type.
   */
  public boolean matchesPath(Path path) {
    return path.getFileName().toString().endsWith(suffix());
  }
}
