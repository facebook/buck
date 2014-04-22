/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.java;

import com.facebook.buck.rules.AnnotationProcessingData;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;

import java.nio.file.Path;

public interface JavaLibrary extends Buildable, HasClasspathEntries, HasJavaAbi {
  /**
   * @return The set of entries to pass to {@code javac}'s {@code -classpath} flag in order to
   *     build a jar associated with this rule.  Contains the classpath entries for the transitive
   *     dependencies of these rules.
   */
  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries();

  /**
   * @return The set of entries to pass to {@code javac}'s {@code -classpath} flag in order to
   *     compile the {@code srcs} associated with this rule.  This set only contains the classpath
   *     entries for those rules that are declared as direct dependencies of this rule.
   */
  public ImmutableSetMultimap<JavaLibrary, Path> getDeclaredClasspathEntries();

  /**
   * @return The set of entries to pass to {@code javac}'s {@code -classpath} flag in order to
   *     compile rules that depend on this rule.
   */
  public ImmutableSetMultimap<JavaLibrary, Path> getOutputClasspathEntries();

  public ImmutableSortedSet<SourcePath> getJavaSrcs();

  public AnnotationProcessingData getAnnotationProcessingData();

  /**
   * Returns a SHA-1 hash that represents the ABI for the Java files returned by
   * {@link #getJavaSrcs()}. If {@link #getJavaSrcs()} returns an empty collection, then this will
   * return a non-absent value. The only requirement on the hash is that equal hashes imply equal
   * ABIs.
   * <p>
   * Because the ABI is computed as part of the build process, this rule cannot be invoked until
   * after this rule is built.
   */
  @Override
  public Sha1HashCode getAbiKey();

  /**
   * @return a (possibly empty) map of names of {@code .class} files in the output of this rule
   *     to SHA-1 hashes of their contents.
   */
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes();

  public static class Data {
    private final Sha1HashCode abiKey;
    private final ImmutableSortedMap<String, HashCode> classNamesToHashes;

    public Data(Sha1HashCode abiKey, ImmutableSortedMap<String, HashCode> classNamesToHashes) {
      this.abiKey = abiKey;
      this.classNamesToHashes = classNamesToHashes;
    }

    public Sha1HashCode getAbiKey() {
      return abiKey;
    }

    public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
      return classNamesToHashes;
    }
  }
}
