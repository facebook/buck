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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Path;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.io.Files;

public class GenerateBuildConfigStep implements Step {

  private String configPackage;
  private boolean debug;
  private Path outBuildConfigPath;

  public GenerateBuildConfigStep(
      String configPackage, boolean debug,
      Path outBuildConfigPath) {
    this.configPackage = Preconditions.checkNotNull(configPackage);
    this.debug = debug;
    this.outBuildConfigPath = Preconditions.checkNotNull(outBuildConfigPath);
  }

  @Override
  public int execute(ExecutionContext context) {

    if (outBuildConfigPath.getNameCount() == 0) {
      throw new HumanReadableException("Output BuildConfig.java filepath is missing");
    }

    try {
      Files.createParentDirs(outBuildConfigPath.toFile());
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }

    File outManifestFile = outBuildConfigPath.toFile();
    try {
      OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(outManifestFile));
      out.write("package ");
      out.write(configPackage);
      out.write(";\n");
      out.write("public final class BuildConfig {\n");
      out.write("  public final static boolean DEBUG = ");
      out.write(String.valueOf(debug));
      out.write(";\n");
      out.write("}");
      out.flush();
      out.close();
    } catch (IOException e) {
      throw new HumanReadableException("Error writing BuildConfig.java");
    }

    if (context.getPlatform() == Platform.WINDOWS) {
      // Convert line endings to Lf on Windows.
      try {
        String xmlText = Files.toString(outManifestFile, Charsets.UTF_8);
        xmlText = xmlText.replace("\r\n", "\n");
        Files.write(xmlText.getBytes(Charsets.UTF_8), outManifestFile);
      } catch (IOException e) {
        throw new HumanReadableException("Error converting line endings of manifest file");
      }
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

    GenerateBuildConfigStep that = (GenerateBuildConfigStep)obj;
    return Objects.equal(this.configPackage, that.configPackage)
        && Objects.equal(this.debug, that.debug)
        && Objects.equal(this.outBuildConfigPath, that.outBuildConfigPath);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        configPackage,
        debug,
        outBuildConfigPath);
  }
}
