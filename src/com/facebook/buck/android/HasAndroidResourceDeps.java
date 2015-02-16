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

package com.facebook.buck.android;

import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.ImmutableSha1HashCode;
import com.facebook.buck.rules.Sha1HashCode;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableCollection;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * Indicates that this class may have android resources that should be packaged into an APK.
 */
public interface HasAndroidResourceDeps extends HasBuildTarget {

  public static final Function<Iterable<HasAndroidResourceDeps>, Sha1HashCode> ABI_HASHER =
      new Function<Iterable<HasAndroidResourceDeps>, Sha1HashCode>() {
        @Override
        public Sha1HashCode apply(Iterable<HasAndroidResourceDeps> deps) {
          Hasher hasher = Hashing.sha1().newHasher();
          for (HasAndroidResourceDeps dep : deps) {
            hasher.putUnencodedChars(dep.getPathToTextSymbolsFile().toString());
            // Avoid collisions by marking end of path explicitly.
            hasher.putChar('\0');
            hasher.putUnencodedChars(dep.getTextSymbolsAbiKey().getHash());
            hasher.putUnencodedChars(dep.getRDotJavaPackage());
            hasher.putChar('\0');
          }
          return ImmutableSha1HashCode.of(hasher.hash().toString());
        }
      };

  public static final Function<HasAndroidResourceDeps, String> TO_R_DOT_JAVA_PACKAGE =
      new Function<HasAndroidResourceDeps, String>() {
        @Override
        public String apply(HasAndroidResourceDeps input) {
          return input.getRDotJavaPackage();
        }
      };

  public static final Predicate<HasAndroidResourceDeps> NON_EMPTY_RESOURCE =
      new Predicate<HasAndroidResourceDeps>() {
        @Override
        public boolean apply(HasAndroidResourceDeps input) {
          return input.getRes() != null;
        }
      };

  public static final Function<HasAndroidResourceDeps, Path> GET_RES_SYMBOLS_TXT =
      new Function<HasAndroidResourceDeps, Path>() {
        @Nullable
        @Override
        public Path apply(HasAndroidResourceDeps input) {
          return input.getPathToTextSymbolsFile();
        }
      };

  /**
   * @return the package name in which to generate the R.java representing these resources.
   */
  String getRDotJavaPackage();

  /**
   * @return path to a temporary directory for storing text symbols.
   */
  Path getPathToTextSymbolsFile();

  /**
   * @return an ABI for the file pointed by {@link #getPathToTextSymbolsFile()}. Since the symbols
   *     text file is essentially a list of resource id, name and type, this is simply a sha1 of
   *     that file.
   */
  Sha1HashCode getTextSymbolsAbiKey();

  /**
   * @return path to a directory containing Android resources.
   */
  @Nullable
  Path getRes();

  /**
   * @return path to a directory containing Android assets.
   */
  @Nullable
  Path getAssets();

  /**
   * See {@link com.facebook.buck.rules.AbstractBuildRule#getInputsToCompareToOutput()}
   *
   * This is used by buildables that need to be rebuilt if any of the inputs of the android
   * resource rules that they depend on change. This allows the buildables to avoid waiting for
   * this rule to finish.
   */
  ImmutableCollection<Path> getInputsToCompareToOutput();
}
