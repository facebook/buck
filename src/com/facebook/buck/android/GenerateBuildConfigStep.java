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

package com.facebook.buck.android;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

import java.io.IOException;
import java.nio.file.Path;

public class GenerateBuildConfigStep implements Step {

  private final String configPackage;
  private final boolean debug;
  private final Path outBuildConfigPath;

  public GenerateBuildConfigStep(
      String configPackage,
      boolean debug,
      Path outBuildConfigPath) {
    this.configPackage = Preconditions.checkNotNull(configPackage);
    this.debug = debug;
    this.outBuildConfigPath = Preconditions.checkNotNull(outBuildConfigPath);
    if (outBuildConfigPath.getNameCount() == 0) {
      throw new HumanReadableException("Output BuildConfig.java filepath is missing");
    }

  }

  @Override
  public int execute(ExecutionContext context) {
    StringBuilder builder = new StringBuilder();
    builder.append("package ");
    builder.append(configPackage);
    builder.append(";\n");
    builder.append("public final class BuildConfig {\n");
    builder.append("  public final static boolean DEBUG = ");
    builder.append(String.valueOf(debug));
    builder.append(";\n");
    builder.append("}");

    ProjectFilesystem filesystem = context.getProjectFilesystem();
    try {
      filesystem.writeContentsToPath(builder.toString(), outBuildConfigPath);
    } catch (IOException e) {
      context.logError(e, "Error writing BuildConfig.java: %s", outBuildConfigPath);
      return 1;
    }

    return 0;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("generate_build_config %s %s", configPackage, String.valueOf(debug));
  }

  @Override
  public String getShortName() {
    return "generate_build_config";
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof GenerateBuildConfigStep)) {
      return false;
    }

    GenerateBuildConfigStep that = (GenerateBuildConfigStep) obj;
    return Objects.equal(this.configPackage, that.configPackage) &&
        Objects.equal(this.debug, that.debug) &&
        Objects.equal(this.outBuildConfigPath, that.outBuildConfigPath);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        configPackage,
        debug,
        outBuildConfigPath);
  }
}
