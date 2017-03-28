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

package com.facebook.buck.ocaml;

import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.OcamlSource;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.versions.VersionPropagator;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

public class OcamlLibraryDescription implements
    Description<OcamlLibraryDescription.Arg>,
    ImplicitDepsInferringDescription<OcamlLibraryDescription.Arg>,
    VersionPropagator<OcamlLibraryDescription.Arg> {

  private final OcamlBuckConfig ocamlBuckConfig;

  public OcamlLibraryDescription(OcamlBuckConfig ocamlBuckConfig) {
    this.ocamlBuckConfig = ocamlBuckConfig;
  }

  public OcamlBuckConfig getOcamlBuckConfig() {
    return ocamlBuckConfig;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      A args) throws NoSuchBuildTargetException {

    ImmutableList<OcamlSource> srcs = args.srcs;
    ImmutableList.Builder<com.facebook.buck.rules.args.Arg> flags = ImmutableList.builder();
    flags.addAll(OcamlDescriptionEnhancer.toStringWithMacrosArgs(
            params.getBuildTarget(),
            cellRoots,
            resolver,
            args.compilerFlags));
    if (ocamlBuckConfig.getWarningsFlags().isPresent() ||
        args.warningsFlags.isPresent()) {
      flags.addAll(
          StringArg.from(
              "-w",
              ocamlBuckConfig.getWarningsFlags().orElse("") + args.warningsFlags.orElse("")));
    }
    ImmutableList<String> linkerflags = args.linkerFlags;

    boolean bytecodeOnly = args.bytecodeOnly.orElse(false);
    boolean nativePlugin = !bytecodeOnly && args.nativePlugin.orElse(false);

    return OcamlRuleBuilder.createBuildRule(
        ocamlBuckConfig,
        params,
        resolver,
        srcs,
        /*isLibrary*/ true,
        bytecodeOnly,
        flags.build(),
        linkerflags,
        nativePlugin);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder.addAll(CxxPlatforms.getParseTimeDeps(ocamlBuckConfig.getCxxPlatform()));
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public ImmutableList<OcamlSource> srcs = ImmutableList.of();
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public ImmutableList<StringWithMacros> compilerFlags = ImmutableList.of();
    public ImmutableList<String> linkerFlags = ImmutableList.of();
    public Optional<String> warningsFlags;
    public Optional<Boolean> bytecodeOnly;
    public Optional<Boolean> nativePlugin;
  }

}
