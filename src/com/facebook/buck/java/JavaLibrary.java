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

import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;

import java.nio.file.Path;

public interface JavaLibrary extends BuildRule, HasClasspathEntries,
    HasJavaAbi, HasJavaClassHashes {

  /**
   * This Buildable is expected to support the GWT flavor, which is a {@link BuildRule} whose output
   * file is a JAR containing the files necessary to use
   * this {@link JavaLibrary} as a GWT module. Normally, this includes Java source code, a .gwt.xml
   * file, and static resources, such as stylesheets and image files.
   * <p>
   * In the event that this {@link JavaLibrary} cannot be represented as a GWT module (for example,
   * if it has no {@code srcs} or {@code resources} of its own, but only exists to export deps),
   * then the flavor will be {@link Optional#absent()}.
   * <p>
   * Note that the output of the {@link BuildRule} for this flavor may contain
   * {@code .class} files. For example, if a third-party releases its {@code .class} and
   * {@code .java} files in the same JAR, it is common for a {@code prebuilt_jar()} to declare that
   * file as both its {@code binary_jar} and its {@code source_jar}. In that case, the output of
   * the {@link BuildRule} will be the original JAR file, which is why it would contain
   * {@code .class} files.
   */
  public static final Flavor GWT_MODULE_FLAVOR = ImmutableFlavor.of("gwt_module");

  /**
   * It's possible to ask a {@link JavaLibrary} to collect its own sources and build a source jar.
   */
  public static final Flavor SRC_JAR = ImmutableFlavor.of("src");

  // TODO(natthu): This can probably be avoided by using a JavaPackageable interface similar to
  // AndroidPackageable.
  public ImmutableSortedSet<BuildRule> getDepsForTransitiveClasspathEntries();

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

  public ImmutableSortedSet<Path> getJavaSrcs();

  public AnnotationProcessingParams getAnnotationProcessingParams();

  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder);

  /**
   * Returns a SHA-1 hash that represents the ABI for the Java files returned by
   * {@link #getJavaSrcs()}. If {@link #getJavaSrcs()} returns an empty collection, then
   * this will return a non-absent value. The only requirement on the hash is that equal hashes
   * imply equal ABIs.
   * <p>
   * Because the ABI is computed as part of the build process, this rule cannot be invoked until
   * after this rule is built.
   */
  @Override
  public Sha1HashCode getAbiKey();

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
