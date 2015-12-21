/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.python;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.python.PythonLibraryDescription.Arg;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class PythonLibraryDescription implements Description<Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("python_library");

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<SourceList> srcs;
    public Optional<PatternMatchedCollection<SourceList>> platformSrcs;
    public Optional<SourceList> resources;
    public Optional<PatternMatchedCollection<SourceList>> platformResources;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<String> baseModule;
    public Optional<Boolean> zipSafe;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> PythonLibrary createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      BuildRuleResolver resolver,
      final A args) {
    final SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    final Path baseModule = PythonUtil.getBasePath(params.getBuildTarget(), args.baseModule);
    return new PythonLibrary(
        params,
        pathResolver,
        new Function<PythonPlatform, ImmutableMap<Path, SourcePath>>() {
          @Override
          public ImmutableMap<Path, SourcePath> apply(PythonPlatform pythonPlatform) {
            return ImmutableMap.<Path, SourcePath>builder()
                .putAll(
                    PythonUtil.toModuleMap(
                        params.getBuildTarget(),
                        pathResolver,
                        "srcs",
                        baseModule,
                        args.srcs.asSet()))
                .putAll(
                    PythonUtil.toModuleMap(
                        params.getBuildTarget(),
                        pathResolver,
                        "platformSrcs",
                        baseModule,
                        args.platformSrcs.get()
                            .getMatchingValues(pythonPlatform.getFlavor().toString())))
                .build();
          }
        },
        new Function<PythonPlatform, ImmutableMap<Path, SourcePath>>() {
          @Override
          public ImmutableMap<Path, SourcePath> apply(PythonPlatform pythonPlatform) {
            return ImmutableMap.<Path, SourcePath>builder()
                .putAll(
                    PythonUtil.toModuleMap(
                        params.getBuildTarget(),
                        pathResolver,
                        "resources",
                        baseModule,
                        args.resources.asSet()))
                .putAll(
                    PythonUtil.toModuleMap(
                        params.getBuildTarget(),
                        pathResolver,
                        "platformResources",
                        baseModule,
                        args.platformResources.get()
                            .getMatchingValues(pythonPlatform.getFlavor().toString())))
                .build();
          }
        },
        args.zipSafe);
  }

}
