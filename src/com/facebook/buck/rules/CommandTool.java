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

package com.facebook.buck.rules;

import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

/**
 * A {@link Tool} based on a list of arguments formed by {@link SourcePath}s.
 *
 * <p>Example:
 *
 * <pre>{@code
 * Tool compiler = new CommandTool.Builder()
 *    .addArg(compilerPath)
 *    .addArg("-I%s", defaultIncludeDir)
 *    .build();
 * }</pre>
 */
public class CommandTool implements Tool {

  private final Optional<Tool> baseTool;
  private final ImmutableList<Arg> args;
  private final ImmutableMap<String, Arg> environment;
  private final ImmutableSortedSet<SourcePath> extraInputs;
  private final ImmutableSortedSet<BuildRule> extraDeps;

  private CommandTool(
      Optional<Tool> baseTool,
      ImmutableList<Arg> args,
      ImmutableMap<String, Arg> environment,
      ImmutableSortedSet<SourcePath> extraInputs,
      ImmutableSortedSet<BuildRule> extraDeps) {
    this.baseTool = baseTool;
    this.args = args;
    this.environment = environment;
    this.extraInputs = extraInputs;
    this.extraDeps = extraDeps;
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    ImmutableSortedSet.Builder<SourcePath> inputs = ImmutableSortedSet.naturalOrder();
    if (baseTool.isPresent()) {
      inputs.addAll(baseTool.get().getInputs());
    }
    for (Arg arg : args) {
      inputs.addAll(arg.getInputs());
    }
    inputs.addAll(extraInputs);
    return inputs.build();
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();
    if (baseTool.isPresent()) {
      deps.addAll(baseTool.get().getDeps(ruleFinder));
    }
    deps.addAll(ruleFinder.filterBuildRuleInputs(getInputs()));
    deps.addAll(extraDeps);
    return deps.build();
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    ImmutableList.Builder<String> command = ImmutableList.builder();
    if (baseTool.isPresent()) {
      command.addAll(baseTool.get().getCommandPrefix(resolver));
    }
    for (Arg arg : args) {
      arg.appendToCommandLine(command, resolver);
    }
    return command.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    ImmutableMap.Builder<String, String> env = ImmutableMap.builder();
    if (baseTool.isPresent()) {
      env.putAll(baseTool.get().getEnvironment(resolver));
    }
    env.putAll(Arg.stringify(environment, resolver));
    return env.build();
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("baseTool", baseTool)
        .setReflectively("args", args)
        .setReflectively("extraInputs", extraInputs);
  }

  // Builder for a `CommandTool`.
  public static class Builder {

    private final Optional<Tool> baseTool;
    private final ImmutableList.Builder<Arg> args = ImmutableList.builder();
    private final ImmutableMap.Builder<String, Arg> environment = ImmutableMap.builder();
    private final ImmutableSortedSet.Builder<SourcePath> extraInputs =
        ImmutableSortedSet.naturalOrder();
    private final ImmutableSortedSet.Builder<BuildRule> extraDeps =
        ImmutableSortedSet.naturalOrder();

    public Builder(Optional<Tool> baseTool) {
      this.baseTool = baseTool;
    }

    public Builder(Tool baseTool) {
      this(Optional.of(baseTool));
    }

    public Builder() {
      this(Optional.empty());
    }

    /** Adds an argument. */
    public Builder addArg(Arg arg) {
      args.add(arg);
      return this;
    }

    public Builder addArg(String arg) {
      return addArg(StringArg.of(arg));
    }

    /** Adds an environment variable key=arg. */
    public Builder addEnv(String key, Arg arg) {
      environment.put(key, arg);
      return this;
    }

    public Builder addEnv(String key, String val) {
      return addEnv(key, StringArg.of(val));
    }

    /** Adds additional non-argument inputs to the tool. */
    public Builder addInputs(Iterable<? extends SourcePath> inputs) {
      extraInputs.addAll(inputs);
      return this;
    }

    public Builder addInput(SourcePath... inputs) {
      return addInputs(ImmutableList.copyOf(inputs));
    }

    /** Adds additional non-argument deps to the tool. */
    public Builder addDeps(Iterable<? extends BuildRule> deps) {
      extraDeps.addAll(deps);
      return this;
    }

    public Builder addDep(BuildRule... deps) {
      return addDeps(ImmutableList.copyOf(deps));
    }

    public CommandTool build() {
      return new CommandTool(
          baseTool, args.build(), environment.build(), extraInputs.build(), extraDeps.build());
    }
  }
}
