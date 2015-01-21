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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImmutableBuildRuleType;
import com.facebook.buck.rules.coercer.OCamlSource;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

public class OCamlBinaryDescription implements
  Description<OCamlBinaryDescription.Arg> {

  public static final BuildRuleType TYPE = ImmutableBuildRuleType.of("ocaml_binary");

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
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {

    ImmutableList<OCamlSource> srcs = args.srcs.get();
    ImmutableList.Builder<String> flags = ImmutableList.builder();
    flags.addAll(args.compilerFlags.get());
    if (args.warningsFlags.isPresent()) {
      flags.add("-w");
      flags.add(args.warningsFlags.get());
    }
    ImmutableList<String> linkerFlags = args.linkerFlags.get();
    return OCamlRuleBuilder.createBuildRule(
        ocamlBuckConfig, params, resolver, srcs, /*isLibrary*/ false, flags.build(), linkerFlags);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<ImmutableList<OCamlSource>> srcs;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<ImmutableList<String>> compilerFlags;
    public Optional<ImmutableList<String>> linkerFlags;
    public Optional<String> warningsFlags;
  }

}
