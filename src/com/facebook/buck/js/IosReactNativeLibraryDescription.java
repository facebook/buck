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

package com.facebook.buck.js;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSet;

import java.util.Collections;

public class IosReactNativeLibraryDescription
    implements
    Description<ReactNativeLibraryArgs>,
    Flavored,
    ImplicitDepsInferringDescription<ReactNativeLibraryArgs> {

  private final ReactNativeLibraryGraphEnhancer enhancer;
  private final Supplier<SourcePath> packager;

  public IosReactNativeLibraryDescription(final ReactNativeBuckConfig buckConfig) {
    this.enhancer = new ReactNativeLibraryGraphEnhancer(buckConfig);
    this.packager = buckConfig::getPackagerSourcePath;
  }

  @Override
  public ReactNativeLibraryArgs createUnpopulatedConstructorArg() {
    return new ReactNativeLibraryArgs();
  }

  @Override
  public <A extends ReactNativeLibraryArgs> ReactNativeBundle createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    return enhancer.enhanceForIos(params, resolver, args);
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return ReactNativeFlavors.validateFlavors(flavors);
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      ReactNativeLibraryArgs constructorArg) {
    return SourcePaths.filterBuildTargetSourcePaths(Collections.singleton(packager.get()));
  }

}
