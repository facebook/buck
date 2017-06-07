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

import com.facebook.buck.jvm.java.HasJavaAbi;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.immutables.value.Value;

public class GroovyLibraryDescription implements Description<GroovyLibraryDescriptionArg> {

  private final GroovyBuckConfig groovyBuckConfig;
  // For cross compilation
  private final JavacOptions defaultJavacOptions;

  public GroovyLibraryDescription(
      GroovyBuckConfig groovyBuckConfig, JavacOptions defaultJavacOptions) {
    this.groovyBuckConfig = groovyBuckConfig;
    this.defaultJavacOptions = defaultJavacOptions;
  }

  @Override
  public Class<GroovyLibraryDescriptionArg> getConstructorArgType() {
    return GroovyLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      GroovyLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {
    JavacOptions javacOptions =
        JavacOptionsFactory.create(defaultJavacOptions, params, resolver, args);
    DefaultGroovyLibraryBuilder defaultGroovyLibraryBuilder =
        new DefaultGroovyLibraryBuilder(
                targetGraph, params, resolver, cellRoots, javacOptions, groovyBuckConfig)
            .setArgs(args);

    return HasJavaAbi.isAbiTarget(params.getBuildTarget())
        ? defaultGroovyLibraryBuilder.buildAbi()
        : defaultGroovyLibraryBuilder.build();
  }

  public interface CoreArg extends JavaLibraryDescription.CoreArg {
    // Groovyc may not play nice with this, so turning it off
    @Override
    default Optional<Boolean> getGenerateAbiFromSource() {
      return Optional.of(false);
    }

    ImmutableList<String> getExtraGroovycArguments();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractGroovyLibraryDescriptionArg extends CoreArg {}
}
