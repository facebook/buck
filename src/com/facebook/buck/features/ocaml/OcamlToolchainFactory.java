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
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.DefaultCxxPlatforms;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.BiFunction;

public class OcamlToolchainFactory implements ToolchainFactory<OcamlToolchain> {

  private static final String SECTION = "ocaml";

  private static final Path DEFAULT_OCAML_COMPILER = Paths.get("ocamlopt.opt");
  private static final Path DEFAULT_OCAML_BYTECODE_COMPILER = Paths.get("ocamlc.opt");
  private static final Path DEFAULT_OCAML_DEP_TOOL = Paths.get("ocamldep.opt");
  private static final Path DEFAULT_OCAML_YACC_COMPILER = Paths.get("ocamlyacc");
  private static final Path DEFAULT_OCAML_DEBUG = Paths.get("ocamldebug");
  private static final Path DEFAULT_OCAML_LEX_COMPILER = Paths.get("ocamllex.opt");

  private OcamlPlatform getPlatform(
      ToolchainCreationContext context, CxxPlatform cxxPlatform, String section) {
    BiFunction<String, Path, ToolProvider> getTool =
        (field, defaultValue) ->
            context
                .getBuckConfig()
                .getView(ToolConfig.class)
                .getToolProvider(section, field)
                .orElseGet(
                    () ->
                        new ConstantToolProvider(
                            new HashedFileTool(
                                () ->
                                    context
                                        .getBuckConfig()
                                        .getPathSourcePath(
                                            context
                                                .getExecutableFinder()
                                                .getExecutable(
                                                    context
                                                        .getBuckConfig()
                                                        .getPath(section, field)
                                                        .orElse(defaultValue),
                                                    context.getEnvironment())))));
    return OcamlPlatform.builder()
        .setOcamlCompiler(getTool.apply("ocaml.compiler", DEFAULT_OCAML_COMPILER))
        .setOcamlDepTool(getTool.apply("dep.tool", DEFAULT_OCAML_DEP_TOOL))
        .setYaccCompiler(getTool.apply("yacc.compiler", DEFAULT_OCAML_YACC_COMPILER))
        .setLexCompiler(getTool.apply("lex.compiler", DEFAULT_OCAML_LEX_COMPILER))
        .setOcamlInteropIncludesDir(context.getBuckConfig().getValue(section, "interop.includes"))
        .setWarningsFlags(context.getBuckConfig().getValue(section, "warnings_flags"))
        .setOcamlBytecodeCompiler(
            getTool.apply("ocaml.bytecode.compiler", DEFAULT_OCAML_BYTECODE_COMPILER))
        .setOcamlDebug(getTool.apply("debug", DEFAULT_OCAML_DEBUG))
        .setCCompiler(cxxPlatform.getCc())
        .setCxxCompiler(cxxPlatform.getCxx())
        .setCPreprocessor(cxxPlatform.getCpp())
        .setCFlags(
            ImmutableList.<String>builder()
                .addAll(cxxPlatform.getCppflags())
                .addAll(cxxPlatform.getCflags())
                .addAll(cxxPlatform.getAsflags())
                .build())
        .setLdFlags(cxxPlatform.getLdflags())
        .setCxxPlatform(cxxPlatform)
        .build();
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
  public Optional<OcamlToolchain> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {

    CxxPlatformsProvider cxxPlatformsProviderFactory =
        toolchainProvider.getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
    FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
        cxxPlatformsProviderFactory.getUnresolvedCxxPlatforms();
    UnresolvedCxxPlatform defaultCxxPlatform =
        cxxPlatformsProviderFactory.getDefaultUnresolvedCxxPlatform();

    FlavorDomain<OcamlPlatform> ocamlPlatforms =
        cxxPlatforms.convert(
            "OCaml platform",
            cxxPlatform ->
                getPlatform(
                    context,
                    cxxPlatform.getLegacyTotallyUnsafe(),
                    getSection(cxxPlatform.getFlavor())));
    OcamlPlatform defaultOcamlPlatform = ocamlPlatforms.getValue(defaultCxxPlatform.getFlavor());

    return Optional.of(OcamlToolchain.of(defaultOcamlPlatform, ocamlPlatforms));
  }
}
