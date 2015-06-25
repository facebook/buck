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
import com.facebook.buck.cxx.Compiler;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.DefaultCxxPlatforms;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class OCamlBuckConfig {

  private static final Path DEFAULT_OCAML_COMPILER = Paths.get("ocamlopt.opt");
  private static final Path DEFAULT_OCAML_BYTECODE_COMPILER = Paths.get("ocamlc.opt");
  private static final Path DEFAULT_OCAML_DEP_TOOL = Paths.get("ocamldep.opt");
  private static final Path DEFAULT_OCAML_YACC_COMPILER = Paths.get("ocamlyacc");
  private static final Path DEFAULT_OCAML_DEBUG = Paths.get("ocamldebug");
  private static final Path DEFAULT_OCAML_LEX_COMPILER = Paths.get("ocamllex.opt");

  private final BuckConfig delegate;
  private final CxxPlatform cxxPlatform;

  public OCamlBuckConfig(
      Platform platform,
      BuckConfig delegate) {
    this.delegate = delegate;
    cxxPlatform = DefaultCxxPlatforms.build(platform, new CxxBuckConfig(delegate));
  }

  public Optional<Path> getOCamlCompiler() {
    return getExecutable("ocaml", "ocaml.compiler", DEFAULT_OCAML_COMPILER);
  }

  public Compiler getCCompiler() {
    return cxxPlatform.getCc();
  }

  public Optional<Path> getOCamlDepTool() {
    return getExecutable("ocaml", "dep.tool", DEFAULT_OCAML_DEP_TOOL);
  }

  public Optional<Path> getYaccCompiler() {
    return getExecutable("ocaml", "yacc.compiler", DEFAULT_OCAML_YACC_COMPILER);
  }

  public Optional<Path> getLexCompiler() {
    return getExecutable("ocaml", "lex.compiler", DEFAULT_OCAML_LEX_COMPILER);
  }

  public Optional<String> getOCamlInteropIncludesDir() {
    return delegate.getValue("ocaml", "interop.includes");
  }

  public Optional<Path> getOCamlBytecodeCompiler() {
    return getExecutable("ocaml", "ocaml.bytecode.compiler", DEFAULT_OCAML_BYTECODE_COMPILER);
  }

  public Compiler getCxxCompiler() {
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
    return getExecutable("ocaml", "debug", DEFAULT_OCAML_DEBUG);
  }

  public CxxPlatform getCxxPlatform() {
    return cxxPlatform;
  }

  private Optional<Path> getExecutable(String section, String label, Path defaultValue) {
    return new ExecutableFinder().getOptionalExecutable(
        delegate.getPath(section, label).or(defaultValue),
        delegate.getEnvironment());
  }
}
