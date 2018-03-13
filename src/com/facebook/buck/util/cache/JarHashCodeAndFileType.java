/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.cache;

import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import javax.annotation.Nullable;

/** HashCodeAndFileType that also stores and caches hashes or file in a jar */
public class JarHashCodeAndFileType extends HashCodeAndFileType {
  private final JarContentHasher jarContentHasher;
  private @Nullable ImmutableMap<Path, HashCodeAndFileType> contents = null;

  protected JarHashCodeAndFileType(
      byte type, HashCode hashCode, JarContentHasher jarContentHasher) {
    super(type, hashCode);
    this.jarContentHasher = jarContentHasher;
  }

  /** Return hash values for all files in an archive (like a JAR) */
  public ImmutableMap<Path, HashCodeAndFileType> getContents() {
    if (contents == null) {
      try {
        contents = jarContentHasher.getContentHashes();
      } catch (IOException e) {
        throw new HumanReadableException(
            "Failed to load hashes from jar: " + jarContentHasher.getJarRelativePath());
      }
    }
    return contents;
  }

  public static HashCodeAndFileType ofArchive(
      HashCode hashCode, JarContentHasher jarContentHasher) {
    return new JarHashCodeAndFileType(TYPE_ARCHIVE, hashCode, jarContentHasher);
  }
}
