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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;
import com.google.common.collect.ComparisonChain;
import java.io.File;

/**
 * A single node in a module graph. It can be either a regular Haskell source module or a hs-boot
 * source module.
 */
@BuckStylePrehashedValue
abstract class HaskellSourceModule implements Comparable<HaskellSourceModule>, AddsToRuleKey {

  static final HaskellSourceModule UNUSED = HaskellSourceModule.from("Unused.hs");

  /** Type of source file for Haskell code. */
  enum SourceType {
    /** An ordinary .hs file which contains code. */
    HsSrcFile,
    /**
     * A .hs-boot file, which is used to break recursive module imports, there will always be a
     * HsSrcFile associated with it. The .hs-boot file must live in the same directory as its parent
     * source file .hs. If you use a literate source file .lhs you must also use a literate boot
     * file, .lhs-boot; and vice versa. Currently, we don't support boot file for .hsc or .chs, but
     * it's possible to support them.
     */
    HsBootFile
  }

  @AddToRuleKey
  abstract String getModuleName();

  @AddToRuleKey
  abstract SourceType getSourceType();

  public static HaskellSourceModule from(String name) {
    return ImmutableHaskellSourceModule.of(
        name.substring(0, name.lastIndexOf('.')).replace(File.separatorChar, '.'),
        name.endsWith("-boot") ? SourceType.HsBootFile : SourceType.HsSrcFile);
  }

  public String getOutputPath(String suffix) {
    if (getSourceType() == SourceType.HsBootFile) {
      suffix = suffix + "-boot";
    }
    return getModuleName().replace('.', File.separatorChar) + "." + suffix;
  }

  @Override
  public String toString() {
    if (getSourceType() == SourceType.HsBootFile) {
      return getModuleName() + "[boot]";
    } else {
      return getModuleName();
    }
  }

  @Override
  public int compareTo(HaskellSourceModule that) {
    if (this == that) {
      return 0;
    }

    return ComparisonChain.start()
        .compare(getModuleName(), that.getModuleName())
        .compare(getSourceType(), that.getSourceType())
        .result();
  }
}
