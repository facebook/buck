/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.scala;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class ScalaBuckConfig {
  private static final String SECTION = "scala";

  private final BuckConfig delegate;

  public ScalaBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public Tool getScalac(BuildRuleResolver resolver) {
    CommandTool.Builder scalac = new CommandTool.Builder(findScalac(resolver));

    // Add some standard options.
    scalac.addArg("-target:" + delegate.getValue(SECTION, "target_level").orElse("jvm-1.7"));

    if (delegate.getBooleanValue(SECTION, "optimize", false)) {
      scalac.addArg("-optimize");
    }

    return scalac.build();
  }

  public BuildTarget getScalaLibraryTarget() {
    return delegate.getRequiredBuildTarget(SECTION, "library");
  }

  public Iterable<BuildTarget> getCompilerPlugins() {
    return delegate.getBuildTargetList(SECTION, "compiler_plugins");
  }

  public Optional<BuildTarget> getScalacTarget() {
    return delegate.getMaybeBuildTarget(SECTION, "compiler");
  }

  public ImmutableList<String> getCompilerFlags() {
    return ImmutableList.copyOf(
        Splitter.on(" ")
            .omitEmptyStrings()
            .split(delegate.getValue(SECTION, "compiler_flags").orElse("")));
  }

  private Tool findScalac(BuildRuleResolver resolver) {
    Optional<Tool> configScalac =
        delegate.getView(ToolConfig.class).getTool(SECTION, "compiler", resolver);
    if (configScalac.isPresent()) {
      return configScalac.get();
    }

    Optional<Path> externalScalac =
        new ExecutableFinder()
            .getOptionalExecutable(Paths.get("scalac"), delegate.getEnvironment());

    if (externalScalac.isPresent()) {
      return new HashedFileTool(() -> delegate.getPathSourcePath(externalScalac.get()));
    }

    String scalaHome = delegate.getEnvironment().get("SCALA_HOME");
    if (scalaHome != null) {
      Path scalacInHomePath = Paths.get(scalaHome, "bin", "scalac");
      if (scalacInHomePath.toFile().exists()) {
        return new HashedFileTool(() -> delegate.getPathSourcePath(scalacInHomePath));
      }
      throw new HumanReadableException("Could not find scalac at $SCALA_HOME/bin/scalac.");
    }

    throw new HumanReadableException(
        "Could not find scalac. Consider setting scala.compiler or $SCALA_HOME.");
  }
}
