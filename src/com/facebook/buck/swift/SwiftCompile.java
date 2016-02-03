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

package com.facebook.buck.swift;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * A build rule which compiles one or more Swift sources into a Swift module.
 */
public class SwiftCompile
    extends AbstractBuildRule {

  @AddToRuleKey
  private final Tool swiftCompiler;

  @AddToRuleKey
  private final String moduleName;

  @AddToRuleKey(stringify = true)
  private final Path outputPath;

  private final Path modulePath;
  private final Path objectPath;

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> srcs;

  public SwiftCompile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Tool swiftCompiler,
      String moduleName,
      Path outputPath,
      Iterable<SourcePath> srcs) {
    super(params, resolver);
    this.swiftCompiler = swiftCompiler;
    this.moduleName = moduleName;
    this.outputPath = outputPath;
    this.modulePath = outputPath.resolve(moduleName + ".swiftmodule");
    this.objectPath = outputPath.resolve(moduleName + ".o");

    this.srcs = ImmutableSortedSet.copyOf(srcs);
  }

  private SwiftCompileStep makeCompileStep() {
    ImmutableList.Builder<String> compilerCommand = ImmutableList.builder();
    compilerCommand.addAll(swiftCompiler.getCommandPrefix(getResolver()));
    compilerCommand.add(
        "-c",
        "-enable-objc-interop",
        "-module-name",
        moduleName,
        "-emit-module",
        "-emit-module-path",
        modulePath.toString(),
        "-o",
        objectPath.toString());
    for (SourcePath sourcePath : srcs) {
      compilerCommand.add(getResolver().getRelativePath(sourcePath).toString());
    }

    return new SwiftCompileStep(
        getProjectFilesystem().getRootPath(),
        ImmutableMap.<String, String>of(),
        compilerCommand.build());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(outputPath);
    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), outputPath),
        makeCompileStep());
  }

  @Override
  public Path getPathToOutput() {
    return outputPath;
  }

  Path getModulePath() {
    return modulePath;
  }

  Path getObjectPath() {
    return objectPath;
  }
}
