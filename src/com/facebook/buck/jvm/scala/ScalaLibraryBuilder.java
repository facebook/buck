/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.scala;

import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.DefaultJavaLibraryBuilder;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

public class ScalaLibraryBuilder extends DefaultJavaLibraryBuilder {
  private final ScalaBuckConfig scalaBuckConfig;
  private ImmutableList<String> extraArguments = ImmutableList.of();

  ScalaLibraryBuilder(
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      ScalaBuckConfig scalaBuckConfig) {
    super(params, buildRuleResolver);
    this.scalaBuckConfig = scalaBuckConfig;
  }

  public ScalaLibraryBuilder setArgs(ScalaLibraryDescription.Arg args) {
    this.setSrcs(args.srcs)
        .setResources(args.resources)
        .setResourcesRoot(args.resourcesRoot)
        .setProvidedDeps(args.providedDeps)
        .setManifestFile(args.manifestFile)
        .setMavenCoords(args.mavenCoords);
    extraArguments = args.extraArguments;

    return this;
  }

  @Override
  protected BuilderHelper newHelper() {
    return new BuilderHelper();
  }

  protected class BuilderHelper extends DefaultJavaLibraryBuilder.BuilderHelper {
    @Override
    protected CompileToJarStepFactory buildCompileStepFactory() {
      ScalaBuckConfig scalaBuckConfig =
          Preconditions.checkNotNull(ScalaLibraryBuilder.this.scalaBuckConfig);

      return new ScalacToJarStepFactory(
          scalaBuckConfig.getScalac(buildRuleResolver),
          buildRuleResolver.getRule(scalaBuckConfig.getScalaLibraryTarget()),
          scalaBuckConfig.getCompilerFlags(),
          extraArguments,
          buildRuleResolver.getAllRules(scalaBuckConfig.getCompilerPlugins()));
    }
  }
}
