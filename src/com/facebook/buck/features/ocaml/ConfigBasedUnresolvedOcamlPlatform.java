/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.impl.DefaultCxxPlatforms;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiFunction;

/** An {@link UnresolvedOcamlPlatform} based on .buckconfig values. */
public class ConfigBasedUnresolvedOcamlPlatform implements UnresolvedOcamlPlatform {

  private static final String SECTION = "ocaml";

  private static final Path DEFAULT_OCAML_COMPILER = Paths.get("ocamlopt.opt");
  private static final Path DEFAULT_OCAML_BYTECODE_COMPILER = Paths.get("ocamlc.opt");
  private static final Path DEFAULT_OCAML_DEP_TOOL = Paths.get("ocamldep.opt");
  private static final Path DEFAULT_OCAML_YACC_COMPILER = Paths.get("ocamlyacc");
  private static final Path DEFAULT_OCAML_DEBUG = Paths.get("ocamldebug");
  private static final Path DEFAULT_OCAML_LEX_COMPILER = Paths.get("ocamllex.opt");

  private final Flavor flavor;
  private final BuckConfig buckConfig;
  private final UnresolvedCxxPlatform unresolvedCxxPlatform;
  private final ExecutableFinder executableFinder;
  private final ImmutableMap<String, String> env;

  public ConfigBasedUnresolvedOcamlPlatform(
      Flavor flavor,
      BuckConfig buckConfig,
      UnresolvedCxxPlatform unresolvedCxxPlatform,
      ExecutableFinder executableFinder,
      ImmutableMap<String, String> env) {
    this.flavor = flavor;
    this.buckConfig = buckConfig;
    this.unresolvedCxxPlatform = unresolvedCxxPlatform;
    this.executableFinder = executableFinder;
    this.env = env;
  }

  private String getSection(Flavor flavor) {
    String section = SECTION;

    // We special case the "default" C/C++ platform to just use the "ocaml" section.
    if (!flavor.equals(DefaultCxxPlatforms.FLAVOR)) {
      section += "#" + flavor;
    }

    return section;
  }

  @Override
  public OcamlPlatform resolve(
      BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
    String section = getSection(flavor);
    CxxPlatform cxxPlatform = unresolvedCxxPlatform.resolve(resolver, targetConfiguration);
    BiFunction<String, Path, ToolProvider> getTool =
        (field, defaultValue) ->
            buckConfig
                .getView(ToolConfig.class)
                .getToolProvider(section, field)
                .orElseGet(
                    () ->
                        new ConstantToolProvider(
                            new HashedFileTool(
                                () ->
                                    buckConfig.getPathSourcePath(
                                        executableFinder.getExecutable(
                                            buckConfig.getPath(section, field).orElse(defaultValue),
                                            env)))));
    return ImmutableOcamlPlatform.builder()
        .setOcamlCompiler(getTool.apply("ocaml.compiler", DEFAULT_OCAML_COMPILER))
        .setOcamlDepTool(getTool.apply("dep.tool", DEFAULT_OCAML_DEP_TOOL))
        .setYaccCompiler(getTool.apply("yacc.compiler", DEFAULT_OCAML_YACC_COMPILER))
        .setLexCompiler(getTool.apply("lex.compiler", DEFAULT_OCAML_LEX_COMPILER))
        .setOcamlInteropIncludesDir(buckConfig.getValue(section, "interop.includes"))
        .setWarningsFlags(buckConfig.getValue(section, "warnings_flags"))
        .setOcamlBytecodeCompiler(
            getTool.apply("ocaml.bytecode.compiler", DEFAULT_OCAML_BYTECODE_COMPILER))
        .setOcamlDebug(getTool.apply("debug", DEFAULT_OCAML_DEBUG))
        .setCCompiler(cxxPlatform.getCc())
        .setCxxCompiler(cxxPlatform.getCxx())
        .setCPreprocessor(cxxPlatform.getCpp())
        .setCFlags(
            ImmutableList.<Arg>builder()
                .addAll(cxxPlatform.getCppflags())
                .addAll(cxxPlatform.getCflags())
                .addAll(cxxPlatform.getAsflags())
                .build())
        .setLdFlags(cxxPlatform.getLdflags())
        .setCxxPlatform(cxxPlatform)
        .build();
  }

  @Override
  public Flavor getFlavor() {
    return flavor;
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    return unresolvedCxxPlatform.getParseTimeDeps(targetConfiguration);
  }
}
