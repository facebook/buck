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
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.OCamlSource;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

public class OCamlBinaryDescription implements
    Description<OCamlBinaryDescription.Arg>,
    ImplicitDepsInferringDescription<OCamlBinaryDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("ocaml_binary");

  private final OCamlBuckConfig ocamlBuckConfig;

  public OCamlBinaryDescription(OCamlBuckConfig ocamlBuckConfig) {
    this.ocamlBuckConfig = ocamlBuckConfig;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> AbstractBuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {

    ImmutableList<OCamlSource> srcs = args.srcs;
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    flags.addAll(args.compilerFlags);
    if (ocamlBuckConfig.getWarningsFlags().isPresent() ||
        args.warningsFlags.isPresent()) {
      flags.add("-w");
      flags.add(ocamlBuckConfig.getWarningsFlags().orElse("") +
          args.warningsFlags.orElse(""));
    }
    ImmutableList<String> linkerFlags = args.linkerFlags;
    return OCamlRuleBuilder.createBuildRule(
        ocamlBuckConfig,
        params,
        resolver,
        srcs,
         /*isLibrary*/ false,
        args.bytecodeOnly.orElse(false),
        flags.build(),
        linkerFlags);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg) {
    return CxxPlatforms.getParseTimeDeps(ocamlBuckConfig.getCxxPlatform());
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public ImmutableList<OCamlSource> srcs = ImmutableList.of();
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public ImmutableList<String> compilerFlags = ImmutableList.of();
    public ImmutableList<String> linkerFlags = ImmutableList.of();
    public Optional<String> warningsFlags;
    public Optional<Boolean> bytecodeOnly;
  }

}
