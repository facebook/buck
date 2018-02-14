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

package com.facebook.buck.python.toolchain.impl;

import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.python.toolchain.PexToolProvider;
import com.facebook.buck.python.toolchain.PythonInterpreter;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.VersionedTool;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.base.Splitter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class DefaultPexToolProvider implements PexToolProvider {

  private static final Path DEFAULT_PATH_TO_PEX =
      Paths.get(System.getProperty("buck.path_to_pex", "src/com/facebook/buck/python/make_pex.py"))
          .toAbsolutePath();

  private final ToolchainProvider toolchainProvider;
  private final PythonBuckConfig pythonBuckConfig;
  private final RuleKeyConfiguration ruleKeyConfiguration;

  public DefaultPexToolProvider(
      ToolchainProvider toolchainProvider,
      PythonBuckConfig pythonBuckConfig,
      RuleKeyConfiguration ruleKeyConfiguration) {
    this.toolchainProvider = toolchainProvider;
    this.pythonBuckConfig = pythonBuckConfig;
    this.ruleKeyConfiguration = ruleKeyConfiguration;
  }

  @Override
  public Tool getPexTool(BuildRuleResolver resolver) {
    CommandTool.Builder builder =
        new CommandTool.Builder(getRawPexTool(resolver, ruleKeyConfiguration));
    for (String flag : Splitter.on(' ').omitEmptyStrings().split(pythonBuckConfig.getPexFlags())) {
      builder.addArg(flag);
    }

    return builder.build();
  }

  private Tool getRawPexTool(
      BuildRuleResolver resolver, RuleKeyConfiguration ruleKeyConfiguration) {
    Optional<Tool> executable = pythonBuckConfig.getRawPexTool(resolver);
    if (executable.isPresent()) {
      return executable.get();
    }

    PythonInterpreter pythonInterpreter =
        toolchainProvider.getByName(PythonInterpreter.DEFAULT_NAME, PythonInterpreter.class);

    return VersionedTool.builder()
        .setName("pex")
        .setVersion(ruleKeyConfiguration.getCoreKey())
        .setPath(pythonInterpreter.getPythonInterpreterPath())
        .addExtraArgs(DEFAULT_PATH_TO_PEX.toString())
        .build();
  }
}
