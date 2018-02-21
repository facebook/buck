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

import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.WriteToFileArg;
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

  @Override
  protected ClasspathMacro parse(
      BuildTarget target, CellPathResolver cellNames, ImmutableList<String> input)
      throws MacroException {
    return ClasspathMacro.of(parseBuildTarget(target, cellNames, input));
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
  public Arg makeExpandToFileArg(BuildTarget target, String prefix, Arg delegate) {
    return new ClassPathWriteToFileArg(target, prefix, delegate);
  }

  @Override
  protected Arg expand(SourcePathResolver resolver, ClasspathMacro ignored, BuildRule rule)
      throws MacroException {
    return new ClasspathArg(
        getHasClasspathEntries(rule)
            .getTransitiveClasspathDeps()
            .stream()
            .map(BuildRule::getSourcePathToOutput)
            .filter(Objects::nonNull)
            .sorted()
            .collect(ImmutableList.toImmutableList()));
  }

  // javac is the canonical reader of classpaths, and its code for reading classpaths from
  // files is a little weird:
  // http://hg.openjdk.java.net/jdk7/jdk7/langtools/file/ce654f4ecfd8/src/share/classes/com/sun/tools/javac/main/CommandLine.java#l74
  // The # characters that might be present in classpaths due to flavoring would be read as
  // comments. As a simple workaround, we quote the entire classpath.
  private static class ClassPathWriteToFileArg extends WriteToFileArg {
    public ClassPathWriteToFileArg(BuildTarget target, String prefix, Arg delegate) {
      super(target, prefix, delegate);
    }

    @Override
    protected String getContent(SourcePathResolver pathResolver) {
      return "'" + super.getContent(pathResolver) + "'";
    }
  }

  private class ClasspathArg implements Arg {
    @AddToRuleKey private final ImmutableList<SourcePath> classpath;

    public ClasspathArg(ImmutableList<SourcePath> collect) {
      this.classpath = collect;
    }

    @Override
    public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
      consumer.accept(
          classpath
              .stream()
              .map(dep -> pathResolver.getAbsolutePath(dep))
              .map(Object::toString)
              .sorted(Ordering.natural())
              .collect(Collectors.joining(File.pathSeparator)));
    }
  }
}
