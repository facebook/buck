/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.SourcePath;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import java.util.HashSet;
import java.util.Optional;

public class JavaLibraryClasspathProvider {

  private JavaLibraryClasspathProvider() {}

  public static ImmutableSet<SourcePath> getOutputClasspathJars(
      JavaLibrary javaLibraryRule, Optional<SourcePath> outputJar) {
    ImmutableSet.Builder<SourcePath> outputClasspathBuilder = ImmutableSet.builder();
    Iterable<JavaLibrary> javaExportedLibraryDeps;
    if (javaLibraryRule instanceof ExportDependencies) {
      javaExportedLibraryDeps =
          getJavaLibraryDeps(((ExportDependencies) javaLibraryRule).getExportedDeps());
    } else {
      javaExportedLibraryDeps = new HashSet<>();
    }

    for (JavaLibrary rule : javaExportedLibraryDeps) {
      outputClasspathBuilder.addAll(rule.getOutputClasspaths());
    }

    if (outputJar.isPresent()) {
      outputClasspathBuilder.add(outputJar.get());
    }

    return outputClasspathBuilder.build();
  }

  public static ImmutableSet<JavaLibrary> getTransitiveClasspathDeps(JavaLibrary javaLibrary) {
    ImmutableSet.Builder<JavaLibrary> classpathDeps = ImmutableSet.builder();

    classpathDeps.addAll(getClasspathDeps(javaLibrary.getDepsForTransitiveClasspathEntries()));

    // Only add ourselves to the classpath if there's a jar to be built or if we're a maven dep.
    if (javaLibrary.getSourcePathToOutput() != null || javaLibrary.getMavenCoords().isPresent()) {
      classpathDeps.add(javaLibrary);
    }

    // Or if there are exported dependencies, to be consistent with getTransitiveClasspaths.
    if (javaLibrary instanceof ExportDependencies
        && !((ExportDependencies) javaLibrary).getExportedDeps().isEmpty()) {
      classpathDeps.add(javaLibrary);
    }

    return classpathDeps.build();
  }

  static FluentIterable<JavaLibrary> getJavaLibraryDeps(Iterable<BuildRule> deps) {
    return FluentIterable.from(deps).filter(JavaLibrary.class);
  }

  /**
   * Include the classpath entries from all JavaLibraryRules that have a direct line of lineage to
   * this rule through other JavaLibraryRules. For example, in the following dependency graph:
   *
   * <p>A / \ B C / \ / \ D E F G
   *
   * <p>If all of the nodes correspond to BuildRules that implement JavaLibraryRule except for B
   * (suppose B is a Genrule), then A's classpath will include C, F, and G, but not D and E. This is
   * because D and E are used to generate B, but do not contribute .class files to things that
   * depend on B. However, if C depended on E as well as F and G, then E would be included in A's
   * classpath.
   */
  public static ImmutableSet<JavaLibrary> getClasspathDeps(Iterable<BuildRule> deps) {
    ImmutableSet.Builder<JavaLibrary> classpathDeps = ImmutableSet.builder();
    for (BuildRule dep : deps) {
      if (dep instanceof HasClasspathEntries) {
        classpathDeps.addAll(((HasClasspathEntries) dep).getTransitiveClasspathDeps());
      }
    }
    return classpathDeps.build();
  }

  /**
   * Given libraries that may contribute classpaths, visit them and collect the classpaths.
   *
   * <p>This is used to generate transitive classpaths from library discovered in a previous
   * traversal.
   */
  public static ImmutableSet<SourcePath> getClasspathsFromLibraries(
      Iterable<JavaLibrary> libraries) {
    ImmutableSet.Builder<SourcePath> classpathEntries = ImmutableSet.builder();
    for (JavaLibrary library : libraries) {
      classpathEntries.addAll(library.getImmediateClasspaths());
    }
    return classpathEntries.build();
  }
}
