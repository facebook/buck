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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.Sha1HashCode;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;

public interface JavaLibraryRule extends Buildable, BuildRule, HasClasspathEntries {
  /**
   * @return The set of entries to pass to {@code javac}'s {@code -classpath} flag in order to
   *     build a jar associated with this rule.  Contains the classpath entries for the transitive
   *     dependencies of these rules.
   */
  @Override
  public ImmutableSetMultimap<JavaLibraryRule, String> getTransitiveClasspathEntries();

  /**
   * @return The set of entries to pass to {@code javac}'s {@code -classpath} flag in order to
   *     compile the {@code srcs} associated with this rule.  This set only contains the classpath
   *     entries for those rules that are declared as direct dependencies of this rule.
   */
  public ImmutableSetMultimap<JavaLibraryRule, String> getDeclaredClasspathEntries();

  /**
   * @return The set of entries to pass to {@code javac}'s {@code -classpath} flag in order to
   *     compile rules that depend on this rule.
   */
  public ImmutableSetMultimap<JavaLibraryRule, String> getOutputClasspathEntries();

  public ImmutableSortedSet<String> getJavaSrcs();

  public AnnotationProcessingData getAnnotationProcessingData();

  /**
   * Returns a SHA-1 hash that represents the ABI for the Java files returned by
   * {@link #getJavaSrcs()}. If {@link #getJavaSrcs()} returns an empty collection, then this will
   * return a non-absent value. The only requirement on the hash is that equal hashes imply equal
   * ABIs.
   * <p>
   * Because the ABI is computed as part of the build process, this rule cannot be invoked until
   * after this rule is built.
   * <p>
   * Returns {@link Optional#absent()} if the rule did not build successfully, or if the ABI could
   * not be extracted for any reason.
   */
  public Optional<Sha1HashCode> getAbiKey() throws IOException;
}
