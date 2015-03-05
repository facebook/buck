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

package com.facebook.buck.rules.macros;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.HasClasspathEntries;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.BuildRule;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Ordering;

import java.io.File;

/**
 * Used to expand the macro {@literal $(classpath //some:target)} to the transitive classpath of
 * that target, expanding all paths to be absolute.
 */
public class ClasspathMacroExpander extends BuildTargetMacroExpander {
  public ClasspathMacroExpander(BuildTargetParser parser) {
    super(parser);
  }

  @Override
  protected String expand(ProjectFilesystem filesystem, BuildRule rule) throws MacroException {
    if (!(rule instanceof HasClasspathEntries)) {
      throw new MacroException(
          String.format(
              "%s used in classpath macro does not correspond to a rule with a java classpath",
              rule.getBuildTarget()));
    }

    HasClasspathEntries hasEntries = (HasClasspathEntries) rule;
    return Joiner.on(File.pathSeparator).join(
        FluentIterable.from(hasEntries.getTransitiveClasspathEntries().values())
            .transform(filesystem.getAbsolutifier())
            .transform(Functions.toStringFunction())
            .toSortedSet(Ordering.natural()));
  }
}
