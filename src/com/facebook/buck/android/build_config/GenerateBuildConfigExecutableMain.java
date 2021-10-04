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

package com.facebook.buck.android.build_config;

import com.facebook.buck.util.ThrowingPrintWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger; // NOPMD

/**
 * Main entry point for generating BuildConfig.java.
 *
 * <p>Expected usage: {@code this_binary <source> <java_package> <use_constant_expressions>
 * <default_values_file> <values_file> <output_path} .
 */
public class GenerateBuildConfigExecutableMain {

  private static final Logger LOG =
      Logger.getLogger(GenerateBuildConfigExecutableMain.class.getName());

  public static void main(String[] args) throws IOException {
    if (args.length < 5 || args.length > 6) {
      LOG.severe(
          "Must specify a source, a java package, whether to use constant expressions, a default values file, an output path and optionally a values file");
      System.exit(1);
    }

    String source = args[0];
    String javaPackage = args[1];
    boolean useConstantExpressions = Boolean.parseBoolean(args[2]);
    Path defaultValuesPath = Paths.get(args[3]);
    Path outputPath = Paths.get(args[4]);

    BuildConfigFields defaultValues =
        BuildConfigFields.fromFieldDeclarations(Files.readAllLines(defaultValuesPath));

    BuildConfigFields fields;
    if (args.length == 5) {
      fields = defaultValues;
    } else {
      Path valuesPath = Paths.get(args[5]);
      fields =
          defaultValues.putAll(
              BuildConfigFields.fromFieldDeclarations(Files.readAllLines(valuesPath)));
    }

    String java =
        BuildConfigs.generateBuildConfigDotJava(
            source, javaPackage, useConstantExpressions, fields);

    try (ThrowingPrintWriter writer =
        new ThrowingPrintWriter(new FileOutputStream(outputPath.toFile()))) {
      writer.printf(java);
    }

    System.exit(0);
  }
}
