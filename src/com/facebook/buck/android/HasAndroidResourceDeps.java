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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.Sha1HashCode;
import com.google.common.base.Function;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.nio.file.Path;

/**
 * Indicates that this class may have android resources that should be packaged into an APK.
 */
public interface HasAndroidResourceDeps {

  public static final Function<Iterable<HasAndroidResourceDeps>, Sha1HashCode> HASHER =
      new Function<Iterable<HasAndroidResourceDeps>, Sha1HashCode>() {
        @Override
        public Sha1HashCode apply(Iterable<HasAndroidResourceDeps> deps) {
          Hasher hasher = Hashing.sha1().newHasher();
          for (HasAndroidResourceDeps dep : deps) {
            hasher.putUnencodedChars(dep.getPathToTextSymbolsFile().toString());
            // Avoid collisions by marking end of path explicitly.
            hasher.putChar('\0');
            hasher.putUnencodedChars(dep.getTextSymbolsAbiKey().getHash());
          }
          return new Sha1HashCode(hasher.hash().toString());
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
  Path getRes();

  /**
   * @return boolean indicating whether this resource rule has whitelisted strings.
   */
  boolean hasWhitelistedStrings();

  BuildTarget getBuildTarget();
}
