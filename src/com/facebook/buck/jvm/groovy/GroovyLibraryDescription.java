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

import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.JvmLibraryConfigurable;
import com.facebook.buck.jvm.java.JvmLibraryConfiguration;
import com.facebook.buck.jvm.java.JvmLibraryDescription;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class GroovyLibraryDescription
    implements JvmLibraryConfigurable<GroovyLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("groovy_library");

  private final GroovyBuckConfig groovyBuckConfig;
  // For cross compilation.
  private final JavacOptions defaultJavacOptions;

  public GroovyLibraryDescription(
      GroovyBuckConfig groovyBuckConfig,
      JavacOptions defaultJavacOptions) {
    this.groovyBuckConfig = groovyBuckConfig;
    this.defaultJavacOptions = defaultJavacOptions;
  }

  @Override
  public JvmLibraryConfiguration createConfiguration(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      GroovyLibraryDescription.Arg args) {

    GroovycToJarStepFactory groovycToJarStepFactory = new GroovycToJarStepFactory(
        groovyBuckConfig.getGroovyCompiler().get(),
        args.extraGroovycArguments,
        JavacOptionsFactory.create(
            defaultJavacOptions,
            params,
            resolver,
            pathResolver,
            args
        ));
    return new JvmLibraryConfiguration(
        groovycToJarStepFactory,
        Optional.<Path>absent(),
        ImmutableList.<BuildRule>of());
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends JvmLibraryDescription.Arg {
    public Optional<ImmutableList<String>> extraGroovycArguments;
  }
}
