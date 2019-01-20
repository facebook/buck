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

import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableSet;

/** Implemented by build rules where the output has a classpath environment. */
public interface HasClasspathEntries {

  /**
   * @return Classpath entries for this rule and its dependencies. e.g. If the rule represents a
   *     java library, then these entries will be passed to {@code javac}'s {@code -classpath} flag
   *     in order to build a jar associated with this rule.
   */
  ImmutableSet<SourcePath> getTransitiveClasspaths();

  /** @return A set of rules contributing classpath entries for this rule and its dependencies. */
  ImmutableSet<JavaLibrary> getTransitiveClasspathDeps();

  /**
   * Returns the classpaths for only this rule, not its deps.
   *
   * <p>Used to generate the value of {@link #getTransitiveClasspaths()}.
   */
  ImmutableSet<SourcePath> getImmediateClasspaths();

  /**
   * @return Classpath entries that this rule will contribute when it is used as a dependency. e.g.
   *     If the rule represents a java library, then these entries must be passed to {@code javac}'s
   *     {@code -classpath} flag in order to compile rules that depend on this rule. This is a
   *     superset of {@code getImmediateClasspaths} which also contains the classpath entries of any
   *     exported deps.
   */
  ImmutableSet<SourcePath> getOutputClasspaths();
}
