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
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.DefaultCxxPlatforms;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.Tool;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;

import java.nio.file.Path;
import java.util.List;

public class OCamlBuckConfig {

  private final BuckConfig delegate;
  private final CxxPlatform cxxPlatform;

  public OCamlBuckConfig(
      Platform platform,
      BuckConfig delegate) {
    this.delegate = delegate;
    cxxPlatform = DefaultCxxPlatforms.build(platform, new CxxBuckConfig(delegate));
  }

  public Optional<Path> getOCamlCompiler() {
    return delegate.getPath("ocaml", "ocaml.compiler");
  }

  public Tool getCCompiler() {
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

  public Optional<String> getOCamlInteropIncludesDir() {
    return delegate.getValue("ocaml", "interop.includes");
  }

  public Optional<Path> getOCamlBytecodeCompiler() {
    return delegate.getPath("ocaml", "ocaml.bytecode.compiler");
  }

  public Tool getCxxCompiler() {
    return cxxPlatform.getCxx();
  }

  public List<String> getCFlags() {
    return cxxPlatform.getCppflags();
  }

  public List<String> getCLinkerFlags() {
    return cxxPlatform.getCxxldflags();
  }

  public List<String> getLdFlags() {
    return cxxPlatform.getLdflags();
  }

  public Linker getLinker() {
    return cxxPlatform.getLd();
  }

  public Optional<Path> getOCamlDebug() {
    return delegate.getPath("ocaml", "debug");
  }

  public CxxPlatform getCxxPlatform() {
    return cxxPlatform;
  }

}
