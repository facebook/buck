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

package com.facebook.buck.cxx;

import com.facebook.buck.io.FileScrubber;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * A specialization of {@link Linker} containing information specific to the Darwin implementation.
 */
public class DarwinLinker implements Linker {

  private final Tool tool;

  public DarwinLinker(Tool tool) {
    this.tool = tool;
  }

  @Override
  public ImmutableCollection<BuildRule> getInputs(SourcePathResolver resolver) {
    return tool.getInputs(resolver);
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return tool.getCommandPrefix(resolver);
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers(Path linkingDirectory) {
    return ImmutableList.of(
        new OsoSymbolsScrubber(linkingDirectory),
        new LcUuidScrubber());
  }

  @Override
  public Iterable<String> linkWhole(String input) {
    return Linkers.iXlinker("-force_load", input);
  }

  @Override
  public Iterable<String> soname(String arg) {
    return Linkers.iXlinker("-install_name", "@rpath/" + arg);
  }

  @Override
  public String origin() {
    return "@executable_path";
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively("tool", tool)
        .setReflectively("type", getClass().getSimpleName());
  }

}
