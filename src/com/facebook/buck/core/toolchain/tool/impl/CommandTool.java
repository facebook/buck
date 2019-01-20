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

package com.facebook.buck.core.toolchain.tool.impl;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

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
  @AddToRuleKey private final Optional<Tool> baseTool;
  @AddToRuleKey private final ImmutableList<Arg> args;
  @AddToRuleKey private final ImmutableSortedMap<String, Arg> environment;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> extraInputs;
  @AddToRuleKey private final ImmutableSortedSet<NonHashableSourcePathContainer> nonHashableInputs;

  private CommandTool(
      Optional<Tool> baseTool,
      ImmutableList<Arg> args,
      ImmutableSortedMap<String, Arg> environment,
      ImmutableSortedSet<SourcePath> extraInputs,
      ImmutableSortedSet<NonHashableSourcePathContainer> nonHashableInputs) {
    this.baseTool = baseTool;
    this.args = args;
    this.environment = environment;
    this.extraInputs = extraInputs;
    this.nonHashableInputs = nonHashableInputs;
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    ImmutableList.Builder<String> command = ImmutableList.builder();
    if (baseTool.isPresent()) {
      command.addAll(baseTool.get().getCommandPrefix(resolver));
    }
    for (Arg arg : args) {
      arg.appendToCommandLine(command::add, resolver);
    }
    return command.build();
  }

  @Override
  public ImmutableSortedMap<String, String> getEnvironment(SourcePathResolver resolver) {
    ImmutableSortedMap.Builder<String, String> env = ImmutableSortedMap.naturalOrder();
    if (baseTool.isPresent()) {
      env.putAll(baseTool.get().getEnvironment(resolver));
    }
    env.putAll(Arg.stringify(environment, resolver));
    return env.build();
  }

  // Builder for a `CommandTool`.
  public static class Builder {

    private final Optional<Tool> baseTool;
    private final ImmutableList.Builder<Arg> args = ImmutableList.builder();
    private final ImmutableSortedMap.Builder<String, Arg> environment =
        ImmutableSortedMap.naturalOrder();
    private final ImmutableSortedSet.Builder<SourcePath> extraInputs =
        ImmutableSortedSet.naturalOrder();
    private final Stream.Builder<SourcePath> nonHashableInputs = Stream.builder();

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

    public Builder addNonHashableInput(SourcePath input) {
      nonHashableInputs.add(input);
      return this;
    }

    public CommandTool build() {
      return new CommandTool(
          baseTool,
          args.build(),
          environment.build(),
          extraInputs.build(),
          nonHashableInputs
              .build()
              .map(NonHashableSourcePathContainer::new)
              .collect(
                  ImmutableSortedSet.toImmutableSortedSet(
                      Comparator.comparing(NonHashableSourcePathContainer::getSourcePath))));
    }
  }
}
