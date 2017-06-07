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
import com.facebook.buck.jvm.scala.ScalaBuckConfig;
import com.facebook.buck.jvm.scala.ScalacToJarStepFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.OptionalCompat;
import com.google.common.collect.ImmutableCollection;
import javax.annotation.Nullable;

public class ScalaAndroidLibraryCompiler extends AndroidLibraryCompiler {
  private final ScalaBuckConfig scalaBuckConfig;
  private @Nullable Tool scalac;

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
  public CompileToJarStepFactory compileToJar(
      AndroidLibraryDescription.CoreArg arg,
      JavacOptions javacOptions,
      BuildRuleResolver resolver) {

    return new ScalacToJarStepFactory(
        getScalac(resolver),
        resolver.getRule(scalaBuckConfig.getScalaLibraryTarget()),
        scalaBuckConfig.getCompilerFlags(),
        arg.getExtraArguments(),
        resolver.getAllRules(scalaBuckConfig.getCompilerPlugins()),
        ANDROID_CLASSPATH_FROM_CONTEXT);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AndroidLibraryDescription.CoreArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {

    extraDepsBuilder
        .add(scalaBuckConfig.getScalaLibraryTarget())
        .addAll(scalaBuckConfig.getCompilerPlugins())
        .addAll(OptionalCompat.asSet(scalaBuckConfig.getScalacTarget()));
  }
}
