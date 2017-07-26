/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public class CommandAliasBuilder
    extends AbstractNodeBuilder<
        CommandAliasDescriptionArg.Builder, CommandAliasDescriptionArg, CommandAliasDescription,
        CommandAlias> {

  private static final CommandAliasDescription aliasBinaryDescription =
      new CommandAliasDescription();
  private final Set<TargetNode<?, ?>> nodes;

  private CommandAliasBuilder(BuildTarget target, Set<TargetNode<?, ?>> nodes) {
    super(aliasBinaryDescription, target);
    this.nodes = nodes;
  }

  public CommandAliasBuilder(BuildTarget target) {
    this(target, new LinkedHashSet<>());
  }

  public CommandAliasBuilder subBuilder(BuildTarget target) {
    return new CommandAliasBuilder(target, nodes);
  }

  public CommandAliasBuilder setExe(BuildTarget exe) {
    nodes.add(GenruleBuilder.newGenruleBuilder(exe, filesystem).setOut("out").build());
    getArgForPopulating().setExe(exe);
    return this;
  }

  public CommandAliasBuilder setExe(BuildTarget exe, TargetNode<?, ?> commandNode) {
    nodes.add(commandNode);
    getArgForPopulating().setExe(exe);
    return this;
  }

  public CommandAliasBuilder setStringArgs(String... stringArgs) {
    getArgForPopulating().setArgs(StringWithMacrosUtils.fromStrings(Arrays.asList(stringArgs)));
    return this;
  }

  public CommandAliasBuilder setMacroArg(String format, Macro... macros) {
    StringWithMacros arg = StringWithMacrosUtils.format(format, macros);
    getArgForPopulating().setArgs(Arrays.asList(arg));
    return this;
  }

  public CommandAliasBuilder setStringEnv(String key, String value) {
    getArgForPopulating().setEnv(ImmutableMap.of(key, StringWithMacrosUtils.format(value)));
    return this;
  }

  public CommandAliasBuilder setMacroEnv(String key, String format, Macro... macros) {
    StringWithMacros value = StringWithMacrosUtils.format(format, macros);
    getArgForPopulating().setEnv(ImmutableMap.of(key, value));
    return this;
  }

  public CommandAliasBuilder addTarget(BuildTarget target) {
    return addTarget(
        GenruleBuilder.newGenruleBuilder(target, filesystem).setOut("arbitrary-file").build());
  }

  public CommandAliasBuilder addTarget(TargetNode<?, ?> targetNode) {
    nodes.add(targetNode);
    return this;
  }

  public BuildResult buildResult() throws NoSuchBuildTargetException {
    nodes.add(build());
    TargetGraph graph = TargetGraphFactory.newInstance(nodes);
    BuildRuleResolver resolver =
        new BuildRuleResolver(graph, new DefaultTargetNodeToBuildRuleTransformer());

    return new BuildResult((CommandAlias) resolver.requireRule(getTarget()), resolver);
  }

  public static class BuildResult {
    private final CommandAlias commandAlias;
    private final SourcePathResolver sourcePathResolver;
    private final BuildRuleResolver resolver;
    private final SourcePathRuleFinder ruleFinder;

    BuildResult(CommandAlias commandAlias, BuildRuleResolver resolver) {
      this.commandAlias = commandAlias;
      ruleFinder = new SourcePathRuleFinder(resolver);
      sourcePathResolver = DefaultSourcePathResolver.from(this.ruleFinder);
      this.resolver = resolver;
    }

    CommandAlias aliasBinary() {
      return commandAlias;
    }

    SourcePathResolver sourcePathResolver() {
      return sourcePathResolver;
    }

    public Path pathOf(BuildTarget target) throws NoSuchBuildTargetException {
      return sourcePathResolver.getAbsolutePath(
          resolver.requireRule(target).getSourcePathToOutput());
    }

    public BuildRuleResolver resolver() {
      return resolver;
    }

    public SourcePathRuleFinder ruleFinder() {
      return ruleFinder;
    }
  }
}
