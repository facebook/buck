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

package com.facebook.buck.jvm.groovy;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.SuggestBuildRules;
import com.facebook.buck.jvm.java.BaseCompileToJarStepFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

class GroovycToJarStepFactory extends BaseCompileToJarStepFactory {

  private final Tool groovyc;
  private final Optional<ImmutableList<String>> extraArguments;
  private final JavacOptions javacOptions;

  public GroovycToJarStepFactory(
      Tool groovyc,
      Optional<ImmutableList<String>> extraArguments,
      JavacOptions javacOptions) {
    this.groovyc = groovyc;
    this.extraArguments = extraArguments;
    this.javacOptions = javacOptions;
  }

  @Override
  public void createCompileStep(
      BuildContext context,
      ImmutableSortedSet<Path> sourceFilePaths,
      BuildTarget invokingRule,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      Path outputDirectory,
      Optional<Path> workingDirectory,
      Optional<Path> pathToSrcsList,
      Optional<SuggestBuildRules> suggestBuildRules,
      /* out params */
      ImmutableList.Builder<Step> steps,
      BuildableContext buildableContext) {
    steps.add(
        new GroovycStep(
            groovyc,
            extraArguments,
            javacOptions,
            resolver,
            outputDirectory,
            sourceFilePaths,
            declaredClasspathEntries,
            filesystem));
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    groovyc.appendToRuleKey(builder);
    return builder
        .setReflectively("extraArguments", extraArguments)
        .setReflectively("javacOptions", javacOptions);
  }
}
