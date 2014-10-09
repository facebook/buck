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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * A build rule which preprocesses, compiles, and assembles a C/C++ source.
 * It supports the execution of one plugin during the compilation.
 */
public class CxxCompile extends AbstractBuildRule {

  private final SourcePath compiler;
  private final Optional<Plugin> plugin;
  private final ImmutableList<String> flags;
  private final Path output;
  private final SourcePath input;
  private final ImmutableList<Path> includeRoots;
  private final ImmutableList<Path> systemIncludeRoots;
  private final ImmutableMap<Path, SourcePath> includes;

  public CxxCompile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePath compiler,
      Optional<Plugin> plugin,
      ImmutableList<String> flags,
      Path output,
      SourcePath input,
      ImmutableList<Path> includeRoots,
      ImmutableList<Path> systemIncludeRoots,
      ImmutableMap<Path, SourcePath> includes) {
    super(params, resolver);
    this.compiler = Preconditions.checkNotNull(compiler);
    this.flags = Preconditions.checkNotNull(flags);
    this.plugin = Preconditions.checkNotNull(plugin);
    this.output = Preconditions.checkNotNull(output);
    this.input = Preconditions.checkNotNull(input);
    this.includeRoots = Preconditions.checkNotNull(includeRoots);
    this.systemIncludeRoots = Preconditions.checkNotNull(systemIncludeRoots);
    this.includes = Preconditions.checkNotNull(includes);
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of(getResolver().getPath(input));
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    builder
        .setInput("compiler", compiler)
        .set("flags", flags)
        .set("output", output.toString());

    if (plugin.isPresent()) {
      Plugin p = plugin.get();
      builder.setInput("plugin-" + p.getName(), p.getPath());
      builder.set("plugin-" + p.getName() + "-flags", p.getFlags());
    }

    // Hash the layout of each potentially included C/C++ header file and it's contents.
    // We do this here, rather than returning them from `getInputsToCompareToOutput` so
    // that we can match the contents hash up with where it was laid out in the include
    // search path, and therefore can accurately capture header file renames.
    for (Path path : ImmutableSortedSet.copyOf(includes.keySet())) {
      SourcePath source = includes.get(path);
      builder.setInput("include(" + path + ")", getResolver().getPath(source));
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
            getResolver().getPath(compiler),
            allFlags,
            output,
            getResolver().getPath(input),
            includeRoots,
            systemIncludeRoots));
  }

  @Override
  public Path getPathToOutputFile() {
    return output;
  }

  public SourcePath getCompiler() {
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

  public ImmutableList<Path> getIncludeRoots() {
    return includeRoots;
  }

  public ImmutableList<Path> getSystemIncludeRoots() {
    return systemIncludeRoots;
  }

  public ImmutableMap<Path, SourcePath> getIncludes() {
    return includes;
  }

  public static class Plugin {

    private String name;
    private Path path;
    private ImmutableList<String> flags;

    public Plugin(String name, Path plugin, ImmutableList<String> flags) {
      this.name = Preconditions.checkNotNull(name);
      this.path = Preconditions.checkNotNull(plugin);
      this.flags = Preconditions.checkNotNull(flags);

      Preconditions.checkState(!name.isEmpty(), "Empty plugin name not allowed.");
      Preconditions.checkState(!flags.isEmpty(), "Empty plugin flags not allowed.");
    }

    public String getName() { return name; }

    public Path getPath() { return path; }

    public ImmutableList<String> getFlags() { return flags; }

  }

}
