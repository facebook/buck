/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.jvm.scala.ScalaBuckConfig;
import com.facebook.buck.jvm.scala.ScalacToJarStepFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.google.common.collect.ImmutableList;

public class ScalaAndroidLibraryCompiler extends AndroidLibraryCompiler {

  private final ScalaBuckConfig scalaBuckConfig;
  private Tool scalac;

  public ScalaAndroidLibraryCompiler(ScalaBuckConfig config) {
    this.scalaBuckConfig = config;
  }

  private Tool getScalac(BuildRuleResolver resolver) {
    if (scalac == null) {
      scalac = scalaBuckConfig.getScalac(resolver);
    }
    return scalac;
  }

  @Override
  public boolean trackClassUsage(JavacOptions javacOptions) {
    return false;
  }

  @Override
  public Iterable<BuildRule> getDeclaredDeps(
      AndroidLibraryDescription.Arg arg,
      BuildRuleResolver resolver) {
    return ImmutableList.of(resolver.getRule(scalaBuckConfig.getScalaLibraryTarget()));
  }

  @Override
  public Iterable<BuildRule> getExtraDeps(
      AndroidLibraryDescription.Arg arg,
      BuildRuleResolver resolver) {
    return getScalac(resolver).getDeps(new SourcePathResolver(resolver));
  }

  @Override
  public CompileToJarStepFactory compileToJar(
      AndroidLibraryDescription.Arg arg,
      JavacOptions javacOptions,
      BuildRuleResolver resolver) {

    return new ScalacToJarStepFactory(
        getScalac(resolver),
        ScalacToJarStepFactory.collectScalacArguments(
            scalaBuckConfig,
            resolver,
            arg.extraArguments),
        ANDROID_CLASSPATH_FROM_CONTEXT);
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AndroidLibraryDescription.Arg constructorArg) {

    return ImmutableList.<BuildTarget>builder()
        .add(scalaBuckConfig.getScalaLibraryTarget())
        .addAll(scalaBuckConfig.getCompilerPlugins())
        .addAll(scalaBuckConfig.getScalacTarget().asSet())
        .build();
  }
}
