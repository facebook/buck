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

import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import java.io.File;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Used to expand the macro {@literal $(classpath //some:target)} to the transitive classpath of
 * that target, expanding all paths to be absolute.
 */
public class ClasspathMacroExpander extends BuildTargetMacroExpander<ClasspathMacro> {

  @Override
  public Class<ClasspathMacro> getInputClass() {
    return ClasspathMacro.class;
  }

  private HasClasspathEntries getHasClasspathEntries(BuildRule rule) throws MacroException {
    if (!(rule instanceof HasClasspathEntries)) {
      throw new MacroException(
          String.format(
              "%s used in classpath macro does not correspond to a rule with a java classpath",
              rule.getBuildTarget()));
    }
    return (HasClasspathEntries) rule;
  }

  @Override
  protected Arg expand(SourcePathResolver resolver, ClasspathMacro ignored, BuildRule rule)
      throws MacroException {
    return new ClasspathArg(
        getHasClasspathEntries(rule).getTransitiveClasspathDeps().stream()
            .map(BuildRule::getSourcePathToOutput)
            .filter(Objects::nonNull)
            .sorted()
            .collect(ImmutableList.toImmutableList()));
  }

  private class ClasspathArg implements Arg {
    @AddToRuleKey private final ImmutableList<SourcePath> classpath;

    public ClasspathArg(ImmutableList<SourcePath> collect) {
      this.classpath = collect;
    }

    @Override
    public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
      consumer.accept(
          classpath.stream()
              .map(dep -> pathResolver.getAbsolutePath(dep))
              .map(Object::toString)
              .sorted(Ordering.natural())
              .collect(Collectors.joining(File.pathSeparator)));
    }
  }
}
