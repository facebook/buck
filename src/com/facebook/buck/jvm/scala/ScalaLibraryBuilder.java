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

public class ScalaLibraryBuilder extends DefaultJavaLibraryBuilder {
  ScalaLibraryBuilder(
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      CompileToJarStepFactory compileStepFactory,
      boolean suggestDependencies) {
    super(params, buildRuleResolver, compileStepFactory, suggestDependencies);
  }

  public DefaultJavaLibraryBuilder setArgs(ScalaLibraryDescription.Arg args) {
    return setSrcs(args.srcs)
        .setResources(args.resources)
        .setResourcesRoot(args.resourcesRoot)
        .setProvidedDeps(args.providedDeps)
        .setManifestFile(args.manifestFile)
        .setMavenCoords(args.mavenCoords);
  }
}
