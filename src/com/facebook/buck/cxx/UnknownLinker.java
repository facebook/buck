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

import com.facebook.buck.io.FileScrubber;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;

/**
 * A specialization of {@link Linker} without information specific to a platform implementation.
 * For instance, no scrubber steps should be given since we do not even know if UnknownLinker.tool
 * is even a linker.
 */
public class UnknownLinker implements Linker {

  private final Tool tool;

  public UnknownLinker(Tool tool) {
    this.tool = tool;
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
  public ImmutableList<FileScrubber> getScrubbers(ImmutableCollection<Path> cellRoots) {
    return ImmutableList.of();
  }

  @Override
  public Iterable<Arg> linkWhole(Arg input) {
    return ImmutableList.of(input);
  }

  @Override
  public Iterable<Arg> linkerMap(Path output) {
    return ImmutableList.<Arg>of();
  }

  @Override
  public Iterable<String> soname(String arg) {
    return ImmutableList.of(arg);
  }

  @Override
  public String searchPathEnvVar() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String preloadEnvVar() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String origin() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String libOrigin() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableList<Arg> createUndefinedSymbolsLinkerArgs(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      BuildTarget target,
      Iterable<? extends SourcePath> symbolFiles) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Iterable<String> getNoAsNeededSharedLibsFlags() {
    return ImmutableList.of();
  }

  @Override
  public Iterable<String> getIgnoreUndefinedSymbolsFlags() {
    return ImmutableList.of();
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    return builder
        .setReflectively("tool", tool)
        .setReflectively("type", getClass().getSimpleName());
  }

}
