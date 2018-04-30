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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.WriteToFileArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import java.io.File;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Used to expand the macro {@literal $(classpath_abi //some:target)} to the transitive abi's jars
 * path of that target, expanding all paths to be absolute.
 */
public class ClasspathAbiMacroExpander extends BuildTargetMacroExpander<ClasspathAbiMacro> {

  @Override
  public Class<ClasspathAbiMacro> getInputClass() {
    return ClasspathAbiMacro.class;
  }

  @Override
  protected ClasspathAbiMacro parse(
      BuildTarget target, CellPathResolver cellNames, ImmutableList<String> input)
      throws MacroException {
    return ClasspathAbiMacro.of(parseBuildTarget(target, cellNames, input));
  }

  private HasClasspathEntries getHasClasspathEntries(BuildRule rule) throws MacroException {
    if (!(rule instanceof HasClasspathEntries)) {
      throw new MacroException(
          String.format(
              "%s used in classpath_abi macro does not correspond to a rule with a java classpath",
              rule.getBuildTarget()));
    }
    return (HasClasspathEntries) rule;
  }

  @Override
  public Arg makeExpandToFileArg(BuildTarget target, String prefix, Arg delegate) {
    return new ClassPathWriteToFileArg(target, prefix, delegate);
  }

  /**
   * Get the class abi jar if present for the rule otherwise return the rule's output
   *
   * @param rule The rule whose jar path needs to be returned
   * @param ruleResolver BuildRuleResolver
   * @return class abi jar or output jar if not found
   */
  @Nullable
  private SourcePath getJarPath(BuildRule rule, BuildRuleResolver ruleResolver) {
    SourcePath jarPath = null;

    if (rule instanceof HasJavaAbi) {
      HasJavaAbi javaAbiRule = (HasJavaAbi) rule;
      Optional<BuildTarget> optionalBuildTarget = javaAbiRule.getAbiJar();
      if (optionalBuildTarget.isPresent()) {
        jarPath = ruleResolver.requireRule(optionalBuildTarget.get()).getSourcePathToOutput();
      }
    }

    if (jarPath == null) {
      jarPath = rule.getSourcePathToOutput();
    }

    return jarPath;
  }

  @Override
  protected Arg expand(SourcePathResolver resolver, ClasspathAbiMacro macro, BuildRule rule)
      throws MacroException {
    throw new MacroException(
        "expand(BuildRuleResolver ruleResolver, ClasspathAbiMacro input) should be called instead");
  }

  @Override
  public Arg expandFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      ClasspathAbiMacro input)
      throws MacroException {

    BuildRule inputRule = resolve(resolver, input);
    return expand(resolver, inputRule);
  }

  protected Arg expand(BuildRuleResolver ruleResolver, BuildRule inputRule) throws MacroException {

    ImmutableList<SourcePath> jarPaths =
        getHasClasspathEntries(inputRule)
            .getTransitiveClasspathDeps()
            .stream()
            .filter(d -> d.getSourcePathToOutput() != null)
            .map(d -> getJarPath(d, ruleResolver))
            .filter(Objects::nonNull)
            .sorted()
            .collect(ImmutableList.toImmutableList());

    return new AbiJarPathArg(jarPaths);
  }

  private static class ClassPathWriteToFileArg extends WriteToFileArg {

    ClassPathWriteToFileArg(BuildTarget target, String prefix, Arg delegate) {
      super(target, prefix, delegate);
    }

    @Override
    protected String getContent(SourcePathResolver pathResolver) {
      return "'" + super.getContent(pathResolver) + "'";
    }
  }

  private class AbiJarPathArg implements Arg {

    @AddToRuleKey private final ImmutableList<SourcePath> classpath;

    AbiJarPathArg(ImmutableList<SourcePath> collect) {
      this.classpath = collect;
    }

    @Override
    public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
      consumer.accept(
          classpath
              .stream()
              .map(pathResolver::getAbsolutePath)
              .map(Object::toString)
              .sorted(Ordering.natural())
              .collect(Collectors.joining(File.pathSeparator)));
    }
  }
}
