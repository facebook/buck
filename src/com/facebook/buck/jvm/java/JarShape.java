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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.HasMavenCoordinates;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public enum JarShape {
  MAVEN {
    @Override
    public Summary gatherDeps(BuildRule root) {
      // Our life is a lot easier if the root is a JavaLibrary :)
      if (!(root instanceof JavaLibrary)) {
        throw new HumanReadableException(
            "Maven jars can only be constructed from java_library targets: %s",
            root.getFullyQualifiedName());
      }

      // We only need the first order maven deps, since maven's depenedency resolution process will
      // pull in any transitive deps. To do this, iterate over our transitive deps and pull out any
      // maven deps. Then remove _their_ deps, and we're done.lo

      ImmutableSet<JavaLibrary> classpathDeps =
          ((HasClasspathEntries) root).getTransitiveClasspathDeps();

      Set<JavaLibrary> toPackage = new HashSet<>(classpathDeps);
      Set<HasMavenCoordinates> mavenDeps =
          toPackage.stream()
              .filter(HasMavenCoordinates::isMavenCoordsPresent)
              .filter(lib -> !lib.equals(root))
              .collect(Collectors.toSet());
      Set<JavaLibrary> toRemoveFromMavenDeps = new HashSet<>();

      for (HasMavenCoordinates mavenDep : mavenDeps) {
        ImmutableSet<JavaLibrary> transitive =
            ((HasClasspathEntries) mavenDep).getTransitiveClasspathDeps();
        toPackage.removeAll(transitive);
        toRemoveFromMavenDeps.addAll(
            transitive.stream().filter(dep -> !dep.equals(mavenDep)).collect(Collectors.toSet()));
      }
      mavenDeps.removeAll(toRemoveFromMavenDeps);

      return new Summary(toPackage, classpathDeps, mavenDeps);
    }
  },
  SINGLE {
    @Override
    public Summary gatherDeps(BuildRule root) {
      if (!(root instanceof HasClasspathEntries)) {
        throw new HumanReadableException(
            "Jars can only be constructed from targets that have a classpath: %s",
            root.getFullyQualifiedName());
      }

      ImmutableSet<JavaLibrary> classpath =
          ((HasClasspathEntries) root).getTransitiveClasspathDeps();

      return new Summary(ImmutableSortedSet.of(root), classpath, ImmutableSortedSet.of());
    }
  },
  ;

  public abstract Summary gatherDeps(BuildRule root);

  public static class Summary {
    private final ImmutableSortedSet<BuildRule> packagedRules;
    private final ImmutableSortedSet<JavaLibrary> classpath;
    private final ImmutableSortedSet<HasMavenCoordinates> mavenDeps;

    Summary(
        Set<? extends BuildRule> packagedRules,
        Set<JavaLibrary> classpath,
        Set<HasMavenCoordinates> mavenDeps) {
      this.packagedRules = ImmutableSortedSet.copyOf(packagedRules);
      this.classpath = ImmutableSortedSet.copyOf(classpath);
      this.mavenDeps = ImmutableSortedSet.copyOf(mavenDeps);
    }

    public ImmutableSortedSet<BuildRule> getPackagedRules() {
      return packagedRules;
    }

    public ImmutableSortedSet<JavaLibrary> getClasspath() {
      return classpath;
    }

    public ImmutableSortedSet<HasMavenCoordinates> getMavenDeps() {
      return mavenDeps;
    }
  }
}
