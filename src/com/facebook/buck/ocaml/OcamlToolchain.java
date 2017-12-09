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

package com.facebook.buck.ocaml;

import com.facebook.buck.cxx.toolchain.CompilerProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.PreprocessorProvider;
import com.facebook.buck.toolchain.Toolchain;
import com.google.common.collect.ImmutableList;

public class OcamlToolchain implements Toolchain {

  public static final String DEFAULT_NAME = "ocaml-toolchain";

  private final CxxPlatform cxxPlatform;

  public OcamlToolchain(CxxPlatform cxxPlatform) {
    this.cxxPlatform = cxxPlatform;
  }

  public CompilerProvider getCCompiler() {
    return cxxPlatform.getCc();
  }

  public PreprocessorProvider getCPreprocessor() {
    return cxxPlatform.getCpp();
  }

  public CompilerProvider getCxxCompiler() {
    return cxxPlatform.getCxx();
  }

  /** @return all C/C++ platform flags used to preprocess, compiler, and assemble C sources. */
  public ImmutableList<String> getCFlags() {
    return ImmutableList.<String>builder()
        .addAll(cxxPlatform.getCppflags())
        .addAll(cxxPlatform.getCflags())
        .addAll(cxxPlatform.getAsflags())
        .build();
  }

  public ImmutableList<String> getLdFlags() {
    return cxxPlatform.getLdflags();
  }
}
