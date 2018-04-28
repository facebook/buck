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

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CompilerProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.PreprocessorProvider;
import com.facebook.buck.toolchain.Toolchain;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.immutables.value.Value;

/** Abstracting the tooling/flags/libraries used to build OCaml rules. */
@Value.Immutable
@BuckStyleImmutable
interface AbstractOcamlPlatform extends Toolchain, FlavorConvertible {

  // TODO: For now, we rely on OCaml platforms having the same "name" as the C/C++ platforms they
  // wrap, due to having to lookup the OCaml platform in the C/C++ interfaces that OCaml rules
  // implement, into which only C/C++ platform objects are threaded.
  @Override
  default Flavor getFlavor() {
    return getCxxPlatform().getFlavor();
  }

  ToolProvider getOcamlCompiler();

  ToolProvider getOcamlDepTool();

  ToolProvider getYaccCompiler();

  ToolProvider getLexCompiler();

  Optional<String> getOcamlInteropIncludesDir();

  Optional<String> getWarningsFlags();

  ToolProvider getOcamlBytecodeCompiler();

  ToolProvider getOcamlDebug();

  CompilerProvider getCCompiler();

  PreprocessorProvider getCPreprocessor();

  CompilerProvider getCxxCompiler();

  /** @return all C/C++ platform flags used to preprocess, compiler, and assemble C sources. */
  ImmutableList<String> getCFlags();

  ImmutableList<String> getLdFlags();

  /** @return the {@link CxxPlatform} to use for C/C++ dependencies. */
  CxxPlatform getCxxPlatform();
}
