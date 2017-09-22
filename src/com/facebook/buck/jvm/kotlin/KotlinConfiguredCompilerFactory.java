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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.jvm.java.ConfiguredCompiler;
import com.facebook.buck.jvm.java.ConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.ExtraClasspathFromContextFunction;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import javax.annotation.Nullable;

public class KotlinConfiguredCompilerFactory extends ConfiguredCompilerFactory {

  private final KotlinBuckConfig kotlinBuckConfig;
  private final JavaBuckConfig javaBuckConfig;
  private final ExtraClasspathFromContextFunction extraClasspathFromContextFunction;

  public KotlinConfiguredCompilerFactory(
      KotlinBuckConfig kotlinBuckConfig, JavaBuckConfig javaBuckConfig) {
    this(kotlinBuckConfig, javaBuckConfig, ExtraClasspathFromContextFunction.EMPTY);
  }

  public KotlinConfiguredCompilerFactory(
      KotlinBuckConfig kotlinBuckConfig,
      JavaBuckConfig javaBuckConfig,
      ExtraClasspathFromContextFunction extraClasspathFromContextFunction) {
    super();
    this.kotlinBuckConfig = kotlinBuckConfig;
    this.javaBuckConfig = javaBuckConfig;
    this.extraClasspathFromContextFunction = extraClasspathFromContextFunction;
  }

  @Override
  public ConfiguredCompiler configure(
      @Nullable JvmLibraryArg args, JavacOptions javacOptions, BuildRuleResolver resolver) {
    return new KotlincToJarStepFactory(
        kotlinBuckConfig.getKotlinc(),
        ((KotlinLibraryDescription.CoreArg) args).getExtraKotlincArguments(),
        extraClasspathFromContextFunction,
        getJavac(resolver, args),
        javacOptions);
  }

  private Javac getJavac(BuildRuleResolver resolver, @Nullable JvmLibraryArg arg) {
    return JavacFactory.create(new SourcePathRuleFinder(resolver), javaBuckConfig, arg);
  }
}
