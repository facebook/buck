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
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Ordering;

import java.io.File;
import java.nio.file.Path;

/**
 * Used to expand the macro {@literal $(classpath //some:target)} to the transitive classpath of
 * that target, expanding all paths to be absolute.
 */
public class ClasspathMacroExpander
    extends BuildTargetMacroExpander
    implements MacroExpanderWithCustomFileOutput {

  private ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries(BuildRule rule)
      throws MacroException {

    if (!(rule instanceof HasClasspathEntries)) {
      throw new MacroException(
          String.format(
              "%s used in classpath macro does not correspond to a rule with a java classpath",
              rule.getBuildTarget()));
    }

    HasClasspathEntries hasEntries = (HasClasspathEntries) rule;
    return hasEntries.getTransitiveClasspathEntries();
  }

  @Override
  public ImmutableList<BuildRule> extractAdditionalBuildTimeDeps(
      BuildTarget target,
      BuildRuleResolver resolver,
      String input)
      throws MacroException {
    return ImmutableList.<BuildRule>copyOf(
        getTransitiveClasspathEntries(resolve(target, resolver, input)).keySet());
  }

  @Override
  public String expandForFile(
      BuildTarget target,
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem,
      String input) throws MacroException {
    // javac is the canonical reader of classpaths, and its code for reading classpaths from
    // files is a little weird:
    // http://hg.openjdk.java.net/jdk7/jdk7/langtools/file/ce654f4ecfd8/src/share/classes/com/sun/tools/javac/main/CommandLine.java#l74
    // The # characters that might be present in classpaths due to flavoring would be read as
    // comments. As a simple workaround, we quote the entire classpath.
    return String.format("'%s'", expand(target, resolver, filesystem, input));
  }

  @Override
  protected String expand(ProjectFilesystem filesystem, BuildRule rule) throws MacroException {
    return Joiner.on(File.pathSeparator).join(
        FluentIterable.from(getTransitiveClasspathEntries(rule).values())
            .transform(filesystem.getAbsolutifier())
            .transform(Functions.toStringFunction())
            .toSortedSet(Ordering.natural()));
  }

}
