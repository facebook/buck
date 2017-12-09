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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.Tool;
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

  public OcamlBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public Optional<Tool> getOcamlCompiler() {
    return getTool(SECTION, "ocaml.compiler", DEFAULT_OCAML_COMPILER);
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

  public Optional<Tool> getOcamlDebug() {
    return getTool(SECTION, "debug", DEFAULT_OCAML_DEBUG);
  }

  private Optional<Path> getExecutable(String section, String label, Path defaultValue) {
    return new ExecutableFinder()
        .getOptionalExecutable(
            delegate.getPath(section, label).orElse(defaultValue), delegate.getEnvironment());
  }

  private Optional<Tool> getTool(String section, String label, Path defaultValue) {
    Optional<Path> executable = getExecutable(section, label, defaultValue);
    return executable.map(path -> new HashedFileTool(() -> delegate.getPathSourcePath(path)));
  }
}
