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

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * A build rule which compiles and assembles a C/C++ source.
 * It supports the execution of one plugin during the compilation.
 */
public class CxxCompile extends AbstractBuildRule {

  private final Tool compiler;
  private final Optional<Plugin> plugin;
  private final ImmutableList<String> flags;
  private final Path output;
  private final SourcePath input;
  private final Optional<DebugPathSanitizer> sanitizer;

  public CxxCompile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Tool compiler,
      Optional<Plugin> plugin,
      ImmutableList<String> flags,
      Path output,
      SourcePath input,
      Optional<DebugPathSanitizer> sanitizer) {
    super(params, resolver);
    this.compiler = compiler;
    this.flags = flags;
    this.plugin = plugin;
    this.output = output;
    this.input = input;
    this.sanitizer = sanitizer;
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return getResolver().filterInputsToCompareToOutput(input);
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    builder
        .setReflectively("compiler", compiler)
        .setReflectively("flags", flags)
        .setReflectively("output", output.toString());

    if (sanitizer.isPresent()) {
      builder.setReflectively("compilationDirectory", sanitizer.get().getCompilationDirectory());
    }

    if (plugin.isPresent()) {
      Plugin p = plugin.get();
      builder.setReflectively("plugin-" + p.getName(), p.getPath());
      builder.setReflectively("plugin-" + p.getName() + "-flags", p.getFlags());
    }

    return builder;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList<String> allFlags =
        plugin.isPresent() ?
            ImmutableList.<String>builder()
                .addAll(flags)
                .addAll(plugin.get().flags)
                .build()
            : flags;

    buildableContext.recordArtifact(output);
    return ImmutableList.of(
        new MkdirStep(output.getParent()),
        new CxxCompileStep(
            compiler.getCommandPrefix(getResolver()),
            allFlags,
            output,
            getResolver().getPath(input),
            sanitizer));
  }

  @Override
  public Path getPathToOutputFile() {
    return output;
  }

  public Tool getCompiler() {
    return compiler;
  }

  public Optional<Plugin> getPlugin() {
    return plugin;
  }

  public ImmutableList<String> getFlags() {
    return flags;
  }

  public Path getOutput() {
    return output;
  }

  public SourcePath getInput() {
    return input;
  }

  public static class Plugin {

    private String name;
    private Path path;
    private ImmutableList<String> flags;

    public Plugin(String name, Path plugin, ImmutableList<String> flags) {
      this.name = name;
      this.path = plugin;
      this.flags = flags;

      Preconditions.checkState(!name.isEmpty(), "Empty plugin name not allowed.");
      Preconditions.checkState(!flags.isEmpty(), "Empty plugin flags not allowed.");
    }

    public String getName() { return name; }

    public Path getPath() { return path; }

    public ImmutableList<String> getFlags() { return flags; }

  }

}
