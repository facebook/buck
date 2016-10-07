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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public abstract class DefaultCompiler implements Compiler {

  private final Tool tool;

  public DefaultCompiler(Tool tool) {
    this.tool = tool;
  }

  @Override
  public Optional<ImmutableList<String>> debugCompilationDirFlags(String debugCompilationDir) {
    return Optional.absent();
  }

  @Override
  public Optional<ImmutableList<String>> getFlagsForColorDiagnostics() {
    return Optional.absent();
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
    return tool.getDeps(resolver);
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return tool.getInputs();
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return tool.getCommandPrefix(resolver);
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    return tool.getEnvironment(resolver);
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink
        .setReflectively("tool", tool)
        .setReflectively("type", getClass().getSimpleName());
  }

  @Override
  public boolean isArgFileSupported() {
    return true;
  }

  @Override
  public boolean isDependencyFileSupported() {
    return true;
  }

  @Override
  public ImmutableList<String> outputArgs(String outputPath) {
    return ImmutableList.of("-o", outputPath);
  }

  @Override
  public ImmutableList<String> outputDependenciesArgs(String outputPath) {
    return ImmutableList.of("-MD", "-MF", outputPath);
  }

  @Override
  public ImmutableList<String> languageArgs(String inputLanguage) {
    return ImmutableList.of("-x", inputLanguage);
  }

  @Override
  public ImmutableList<String> getPdcFlags() {
    return ImmutableList.of();
  }

  @Override
  public ImmutableList<String> getPicFlags() {
    return ImmutableList.of("-fPIC");
  }
}
