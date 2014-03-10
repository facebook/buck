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

package com.facebook.buck.java;

import com.facebook.buck.rules.BuildRule;
import com.google.common.collect.ImmutableSetMultimap;

import java.util.Set;

public class Classpaths {
  private Classpaths() {
    // Utility class
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
  public static ImmutableSetMultimap<JavaLibraryRule, String> getClasspathEntries(
      Set<BuildRule> deps) {
    final ImmutableSetMultimap.Builder<JavaLibraryRule, String> classpathEntries =
        ImmutableSetMultimap.builder();
    for (BuildRule dep : deps) {
      if (dep instanceof JavaLibraryRule) {
        JavaLibraryRule libraryRule = (JavaLibraryRule)dep;
        classpathEntries.putAll(libraryRule.getTransitiveClasspathEntries());
      }
    }
    return classpathEntries.build();
  }
}
