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

package com.facebook.buck.jvm.core;

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.util.Optional;
import java.util.Set;

public interface JavaLibrary
    extends BuildRule,
        HasClasspathEntries,
        HasClasspathDeps,
        HasDesugarSupport,
        HasJavaAbi,
        HasJavaClassHashes,
        HasMavenCoordinates,
        HasSources {

  /**
   * This Buildable is expected to support the GWT flavor, which is a {@link BuildRule} whose output
   * file is a JAR containing the files necessary to use this {@link JavaLibrary} as a GWT module.
   * Normally, this includes Java source code, a .gwt.xml file, and static resources, such as
   * stylesheets and image files.
   *
   * <p>In the event that this {@link JavaLibrary} cannot be represented as a GWT module (for
   * example, if it has no {@code srcs} or {@code resources} of its own, but only exists to export
   * deps), then the flavor will be {@link Optional#empty()}.
   *
   * <p>Note that the output of the {@link BuildRule} for this flavor may contain {@code .class}
   * files. For example, if a third-party releases its {@code .class} and {@code .java} files in the
   * same JAR, it is common for a {@code prebuilt_jar()} to declare that file as both its {@code
   * binary_jar} and its {@code source_jar}. In that case, the output of the {@link BuildRule} will
   * be the original JAR file, which is why it would contain {@code .class} files.
   */
  Flavor GWT_MODULE_FLAVOR = InternalFlavor.of("gwt_module");

  /**
   * It's possible to ask a {@link JavaLibrary} to collect its own sources and build a source jar.
   */
  Flavor SRC_JAR = InternalFlavor.of("src");

  /**
   * For maven publishing only dependencies containing maven coordinates will be listed as
   * dependencies. Others will be packaged-in, and their first-order dependencies considered in the
   * same manner
   */
  Flavor MAVEN_JAR = InternalFlavor.of("maven");

  // TODO(natthu): This can probably be avoided by using a JavaPackageable interface similar to
  // AndroidPackageable.
  @Override
  Set<BuildRule> getDepsForTransitiveClasspathEntries();

  ImmutableSortedSet<SourcePath> getJavaSrcs();

  @Override
  ImmutableSortedSet<SourcePath> getSources();

  ImmutableSortedSet<SourcePath> getResources();

  Optional<String> getResourcesRoot();

  Optional<SourcePath> getGeneratedAnnotationSourcePath();

  boolean hasAnnotationProcessing();

  class Data {
    private final ImmutableSortedMap<String, HashCode> classNamesToHashes;

    public Data(ImmutableSortedMap<String, HashCode> classNamesToHashes) {
      this.classNamesToHashes = classNamesToHashes;
    }

    public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
      return classNamesToHashes;
    }
  }
}
