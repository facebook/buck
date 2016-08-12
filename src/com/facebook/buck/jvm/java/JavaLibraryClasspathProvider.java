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

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Set;

public class JavaLibraryClasspathProvider {

  private JavaLibraryClasspathProvider() {
  }

  public static ImmutableSet<Path> getOutputClasspathJars(
      JavaLibrary javaLibraryRule,
      SourcePathResolver resolver,
      Optional<SourcePath> outputJar) {
    ImmutableSet.Builder<Path> outputClasspathBuilder =
        ImmutableSet.builder();
    Iterable<JavaLibrary> javaExportedLibraryDeps;
    if (javaLibraryRule instanceof ExportDependencies) {
      javaExportedLibraryDeps =
          getJavaLibraryDeps(((ExportDependencies) javaLibraryRule).getExportedDeps());
    } else {
      javaExportedLibraryDeps = Sets.newHashSet();
    }

    for (JavaLibrary rule : javaExportedLibraryDeps) {
      outputClasspathBuilder.addAll(rule.getOutputClasspathEntries());
    }

    if (outputJar.isPresent()) {
      outputClasspathBuilder.add(resolver.getAbsolutePath(outputJar.get()));
    }

    return outputClasspathBuilder.build();
  }

  public static ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries(
      JavaLibrary javaLibraryRule,
      SourcePathResolver resolver,
      Optional<SourcePath> outputJar) {
    final ImmutableSetMultimap.Builder<JavaLibrary, Path> classpathEntries =
        ImmutableSetMultimap.builder();
    ImmutableSetMultimap<JavaLibrary, Path> classpathEntriesForDeps =
        getClasspathEntries(javaLibraryRule.getDepsForTransitiveClasspathEntries());

    ImmutableSetMultimap<JavaLibrary, Path> classpathEntriesForExportedsDeps;
    if (javaLibraryRule instanceof ExportDependencies) {
      classpathEntriesForExportedsDeps =
          getClasspathEntries(((ExportDependencies) javaLibraryRule).getExportedDeps());
    } else {
      classpathEntriesForExportedsDeps = ImmutableSetMultimap.of();
    }

    classpathEntries.putAll(classpathEntriesForDeps);

    // If we have any exported deps, add an entry mapping ourselves to to their classpaths,
    // so when suggesting libraries to add we know that adding this library would pull in
    // it's deps.
    if (!classpathEntriesForExportedsDeps.isEmpty()) {
      classpathEntries.putAll(
          javaLibraryRule,
          classpathEntriesForExportedsDeps.values());
    }

    // Only add ourselves to the classpath if there's a jar to be built.
    if (outputJar.isPresent()) {
      classpathEntries.put(javaLibraryRule, resolver.getAbsolutePath(outputJar.get()));
    }

    return classpathEntries.build();
  }

  public static ImmutableSet<JavaLibrary> getTransitiveClasspathDeps(
      JavaLibrary javaLibrary,
      Optional<SourcePath> outputJar) {
    ImmutableSet.Builder<JavaLibrary> classpathDeps = ImmutableSet.builder();

    classpathDeps.addAll(
        getClasspathDeps(
            javaLibrary.getDepsForTransitiveClasspathEntries()));

    // Only add ourselves to the classpath if there's a jar to be built.
    if (outputJar.isPresent()) {
      classpathDeps.add(javaLibrary);
    }

    return classpathDeps.build();
  }

  public static ImmutableSetMultimap<JavaLibrary, Path> getDeclaredClasspathEntries(
      JavaLibrary javaLibraryRule) {
    final ImmutableSetMultimap.Builder<JavaLibrary, Path> classpathEntries =
        ImmutableSetMultimap.builder();

    Iterable<JavaLibrary> javaLibraryDeps = getJavaLibraryDeps(
        javaLibraryRule.getDepsForTransitiveClasspathEntries());

    for (JavaLibrary rule : javaLibraryDeps) {
      for (Path path : rule.getOutputClasspathEntries()) {
        classpathEntries.put(rule, rule.getProjectFilesystem().resolve(path));
      }
    }
    return classpathEntries.build();
  }

  static FluentIterable<JavaLibrary> getJavaLibraryDeps(Iterable<BuildRule> deps) {
    return FluentIterable.from(deps).filter(JavaLibrary.class);
  }

  /**
   * Include the classpath entries from all JavaLibraryRules that have a direct line of lineage
   * to this rule through other JavaLibraryRules. For example, in the following dependency graph:
   *
   *        A
   *      /   \
   *     B     C
   *    / \   / \
   *    D E   F G
   *
   * If all of the nodes correspond to BuildRules that implement JavaLibraryRule except for
   * B (suppose B is a Genrule), then A's classpath will include C, F, and G, but not D and E.
   * This is because D and E are used to generate B, but do not contribute .class files to things
   * that depend on B. However, if C depended on E as well as F and G, then E would be included in
   * A's classpath.
   */
  public static ImmutableSetMultimap<JavaLibrary, Path> getClasspathEntries(
      Set<BuildRule> deps) {
    final ImmutableSetMultimap.Builder<JavaLibrary, Path> classpathEntries =
        ImmutableSetMultimap.builder();
    for (BuildRule dep : deps) {
      JavaLibrary library = null;
      if (dep instanceof JavaLibrary) {
        library = (JavaLibrary) dep;
      }

      if (library != null) {
        classpathEntries.putAll(library.getTransitiveClasspathEntries());
      }
    }
    return classpathEntries.build();
  }

  public static ImmutableSet<JavaLibrary> getClasspathDeps(Iterable<BuildRule> deps) {
    ImmutableSet.Builder<JavaLibrary> classpathDeps = ImmutableSet.builder();
    for (BuildRule dep : deps) {
      if (dep instanceof JavaLibrary) {
        classpathDeps.addAll(((JavaLibrary) dep).getTransitiveClasspathDeps());
      }
    }
    return classpathDeps.build();
  }
}
