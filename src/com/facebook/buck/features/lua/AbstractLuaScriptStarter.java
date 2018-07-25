/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.features.lua;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.WriteStringTemplateRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.Escaper;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/** {@link Starter} implementation which builds a starter as a Lua script. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractLuaScriptStarter implements Starter {

  private static final String STARTER = "starter.lua.in";

  abstract ProjectFilesystem getProjectFilesystem();

  abstract BuildTarget getBaseTarget();

  abstract BuildRuleParams getBaseParams();

  abstract ActionGraphBuilder getActionGraphBuilder();

  abstract SourcePathResolver getPathResolver();

  abstract SourcePathRuleFinder getRuleFinder();

  abstract LuaPlatform getLuaPlatform();

  abstract BuildTarget getTarget();

  abstract Path getOutput();

  abstract String getMainModule();

  abstract Optional<Path> getRelativeModulesDir();

  abstract Optional<Path> getRelativePythonModulesDir();

  private String getPureStarterTemplate() {
    try {
      return Resources.toString(
          Resources.getResource(AbstractLuaScriptStarter.class, STARTER), Charsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public SourcePath build() {
    BuildTarget templateTarget =
        getBaseTarget().withAppendedFlavors(InternalFlavor.of("starter-template"));
    WriteFile templateRule =
        getActionGraphBuilder()
            .addToIndex(
                new WriteFile(
                    templateTarget,
                    getProjectFilesystem(),
                    getPureStarterTemplate(),
                    BuildTargetPaths.getGenPath(
                        getProjectFilesystem(), templateTarget, "%s/starter.lua.in"),
                    /* executable */ false));

    Tool lua = getLuaPlatform().getLua().resolve(getActionGraphBuilder());
    WriteStringTemplateRule writeStringTemplateRule =
        getActionGraphBuilder()
            .addToIndex(
                WriteStringTemplateRule.from(
                    getProjectFilesystem(),
                    getBaseParams(),
                    getRuleFinder(),
                    getTarget(),
                    getOutput(),
                    templateRule.getSourcePathToOutput(),
                    ImmutableMap.of(
                        "SHEBANG",
                        lua.getCommandPrefix(getPathResolver()).get(0),
                        "MAIN_MODULE",
                        Escaper.escapeAsPythonString(getMainModule()),
                        "MODULES_DIR",
                        getRelativeModulesDir().isPresent()
                            ? Escaper.escapeAsPythonString(getRelativeModulesDir().get().toString())
                            : "nil",
                        "PY_MODULES_DIR",
                        getRelativePythonModulesDir().isPresent()
                            ? Escaper.escapeAsPythonString(
                                getRelativePythonModulesDir().get().toString())
                            : "nil",
                        "EXT_SUFFIX",
                        Escaper.escapeAsPythonString(
                            getLuaPlatform().getCxxPlatform().getSharedLibraryExtension())),
                    /* executable */ true));

    return writeStringTemplateRule.getSourcePathToOutput();
  }
}
