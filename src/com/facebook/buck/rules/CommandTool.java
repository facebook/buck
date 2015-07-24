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

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * A {@link Tool} based on a list of arguments formed by {@link SourcePath}s.
 *
 * Example:
 * <pre>
 * {@code
 *   Tool compiler = new CommandTool.Builder()
 *      .addArg(compilerPath)
 *      .addArg("-I%s", defaultIncludeDir)
 *      .build();
 * }
 * </pre>
 */
public class CommandTool implements Tool {

  private final ImmutableList<Arg> args;
  private final ImmutableSortedSet<SourcePath> extraInputs;

  private CommandTool(ImmutableList<Arg> args, ImmutableSortedSet<SourcePath> extraInputs) {
    this.args = args;
    this.extraInputs = extraInputs;
  }

  @Override
  public ImmutableCollection<BuildRule> getInputs(SourcePathResolver resolver) {
    ImmutableSortedSet.Builder<BuildRule> inputs = ImmutableSortedSet.naturalOrder();
    for (Arg arg : args) {
      inputs.addAll(resolver.filterBuildRuleInputs(arg.getInputs()));
    }
    inputs.addAll(resolver.filterBuildRuleInputs(extraInputs));
    return inputs.build();
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    ImmutableList.Builder<String> rules = ImmutableList.builder();
    for (Arg arg : args) {
      rules.add(arg.format(resolver));
    }
    return rules.build();
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively("args", args)
        .setReflectively("extraInputs", extraInputs);
  }

  // Builder for a `CommandTool`.
  public static class Builder {

    private final ImmutableList.Builder<Arg> args = ImmutableList.builder();
    private final ImmutableSortedSet.Builder<SourcePath> extraInputs =
        ImmutableSortedSet.naturalOrder();

    /**
     * Add a {@link String} argument represented by the format string to the command.  The
     * {@code inputs} will be resolved and used to format the format string when this argument is
     * added to the command.
     *
     * @param format the format string representing the argument.
     * @param inputs {@link SourcePath}s to use when formatting {@code format}.
     */
    public Builder addArg(String format, SourcePath... inputs) {
      args.add(new Arg(format, ImmutableList.copyOf(inputs)));
      return this;
    }

    /**
     * Add a `SourcePath` as an argument to the command.
     */
    public Builder addArg(SourcePath input) {
      return addArg("%s", input);
    }

    /**
     * Adds additional non-argument inputs to the tool.
     */
    public Builder addInputs(Iterable<? extends SourcePath> inputs) {
      extraInputs.addAll(inputs);
      return this;
    }

    public Builder addInput(SourcePath... inputs) {
      return addInputs(ImmutableList.copyOf(inputs));
    }

    public CommandTool build() {
      return new CommandTool(args.build(), extraInputs.build());
    }

  }

  // Represents a single "argument" in the command list.
  private static class Arg implements RuleKeyAppendable {

    private final String format;
    private final ImmutableList<SourcePath> inputs;

    public Arg(String format, ImmutableList<SourcePath> inputs) {
      this.format = format;
      this.inputs = inputs;
    }

    public String format(SourcePathResolver resolver) {
      return String.format(
          format,
          (Object[]) FluentIterable.from(ImmutableList.copyOf(inputs))
              .transform(resolver.getResolvedPathFunction())
              .toArray(Path.class));
    }

    public ImmutableList<SourcePath> getInputs() {
      return inputs;
    }

    @Override
    public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
      return builder
          .setReflectively("format", format)
          .setReflectively("inputs", inputs);
    }

  }

}
