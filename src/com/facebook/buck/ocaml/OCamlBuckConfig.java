/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.DefaultCxxPlatform;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

public class OCamlBuckConfig {

  private final BuckConfig delegate;
  private final CxxPlatform cxxPlatform;

  public OCamlBuckConfig(BuckConfig delegate) {
    this.delegate = Preconditions.checkNotNull(delegate);
    cxxPlatform = new DefaultCxxPlatform(delegate);
  }

  public Optional<Path> getOCamlCompiler() {
    return delegate.getPath("ocaml", "ocaml.compiler");
  }

  public SourcePath getCCompiler() {
    return cxxPlatform.getCc();
  }

  public Optional<Path> getOCamlDepTool() {
    return delegate.getPath("ocaml", "dep.tool");
  }

  public Optional<Path> getYaccCompiler() {
    return delegate.getPath("ocaml", "yacc.compiler");
  }

  public Optional<Path> getLexCompiler() {
    return delegate.getPath("ocaml", "lex.compiler");
  }

  public Optional<Path> getOCamlBytecodeCompiler() {
    return delegate.getPath("ocaml", "ocaml.bytecode.compiler");
  }

  public SourcePath getCxxCompiler() {
    return cxxPlatform.getCxx();
  }
}
