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
import com.facebook.buck.model.HasTests;
import com.facebook.buck.python.PythonLibraryDescription.Arg;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Collections;

public class PythonLibraryDescription implements Description<Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("python_library");

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
        pythonPlatform -> ImmutableMap.<Path, SourcePath>builder()
            .putAll(
                PythonUtil.toModuleMap(
                    params.getBuildTarget(),
                    pathResolver,
                    "srcs",
                    baseModule,
                    Collections.singleton(args.srcs)))
            .putAll(
                PythonUtil.toModuleMap(
                    params.getBuildTarget(),
                    pathResolver,
                    "platformSrcs",
                    baseModule,
                    args.platformSrcs.getMatchingValues(pythonPlatform.getFlavor().toString())))
            .build(),
        pythonPlatform -> ImmutableMap.<Path, SourcePath>builder()
            .putAll(
                PythonUtil.toModuleMap(
                    params.getBuildTarget(),
                    pathResolver,
                    "resources",
                    baseModule,
                    Collections.singleton(args.resources)))
            .putAll(
                PythonUtil.toModuleMap(
                    params.getBuildTarget(),
                    pathResolver,
                    "platformResources",
                    baseModule,
                    args.platformResources
                        .getMatchingValues(pythonPlatform.getFlavor().toString())))
            .build(),
        args.zipSafe);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg implements HasTests {
    public SourceList srcs = SourceList.EMPTY;
    public PatternMatchedCollection<SourceList> platformSrcs = PatternMatchedCollection.of();
    public SourceList resources = SourceList.EMPTY;
    public PatternMatchedCollection<SourceList> platformResources = PatternMatchedCollection.of();
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public Optional<String> baseModule;
    public Optional<Boolean> zipSafe;
    @Hint(isDep = false) public ImmutableSortedSet<BuildTarget> tests = ImmutableSortedSet.of();

    @Override
    public ImmutableSortedSet<BuildTarget> getTests() {
      return tests;
    }

  }

}
