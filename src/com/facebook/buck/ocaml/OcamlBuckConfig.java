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
import com.facebook.buck.cxx.CompilerProvider;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.PreprocessorProvider;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.Tool;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class OcamlBuckConfig {

  private static final String SECTION = "ocaml";

  private static final Path DEFAULT_OCAML_COMPILER = Paths.get("ocamlopt.opt");
  private static final Path DEFAULT_OCAML_BYTECODE_COMPILER = Paths.get("ocamlc.opt");
  private static final Path DEFAULT_OCAML_DEP_TOOL = Paths.get("ocamldep.opt");
  private static final Path DEFAULT_OCAML_YACC_COMPILER = Paths.get("ocamlyacc");
  private static final Path DEFAULT_OCAML_DEBUG = Paths.get("ocamldebug");
  private static final Path DEFAULT_OCAML_LEX_COMPILER = Paths.get("ocamllex.opt");

  private final BuckConfig delegate;
  private final CxxPlatform cxxPlatform;

  public OcamlBuckConfig(BuckConfig delegate, CxxPlatform cxxPlatform) {
    this.delegate = delegate;
    this.cxxPlatform = cxxPlatform;
  }

  public Optional<Tool> getOcamlCompiler() {
    return getTool(SECTION, "ocaml.compiler", DEFAULT_OCAML_COMPILER);
  }

  public CompilerProvider getCCompiler() {
    return cxxPlatform.getCc();
  }

  public PreprocessorProvider getCPreprocessor() {
    return cxxPlatform.getCpp();
  }

  public Optional<Tool> getOcamlDepTool() {
    return getTool(SECTION, "dep.tool", DEFAULT_OCAML_DEP_TOOL);
  }

  public Optional<Tool> getYaccCompiler() {
    return getTool(SECTION, "yacc.compiler", DEFAULT_OCAML_YACC_COMPILER);
  }

  public Optional<Tool> getLexCompiler() {
    return getTool(SECTION, "lex.compiler", DEFAULT_OCAML_LEX_COMPILER);
  }

  public Optional<String> getOcamlInteropIncludesDir() {
    return delegate.getValue(SECTION, "interop.includes");
  }

  public Optional<String> getWarningsFlags() {
    return delegate.getValue(SECTION, "warnings_flags");
  }

  public Optional<Tool> getOcamlBytecodeCompiler() {
    return getTool(SECTION, "ocaml.bytecode.compiler", DEFAULT_OCAML_BYTECODE_COMPILER);
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

  public Optional<Tool> getOcamlDebug() {
    return getTool(SECTION, "debug", DEFAULT_OCAML_DEBUG);
  }

  public CxxPlatform getCxxPlatform() {
    return cxxPlatform;
  }

  private Optional<Path> getExecutable(String section, String label, Path defaultValue) {
    return new ExecutableFinder()
        .getOptionalExecutable(
            delegate.getPath(section, label).orElse(defaultValue), delegate.getEnvironment());
  }

  private Optional<Tool> getTool(String section, String label, Path defaultValue) {
    Optional<Path> executable = getExecutable(section, label, defaultValue);
    if (!executable.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(new HashedFileTool(executable.get()));
  }
}
