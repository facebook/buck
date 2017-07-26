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

import static org.hamcrest.Matchers.in;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.macros.LocationMacro;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.stream.Collectors;
import org.junit.Test;

public class CommandAliasDescriptionTest {
  private static final BuildTarget aliasBinaryTarget =
      BuildTargetFactory.newInstance("//arbitrary:alias");
  private static final BuildTarget delegate =
      BuildTargetFactory.newInstance("//wrapped/command/to:run");
  private static final BuildTarget argTarget = BuildTargetFactory.newInstance("//macro:dependency");

  @Test
  public void runsBuildTargetsAsCommand() throws NoSuchBuildTargetException {
    CommandAliasBuilder.BuildResult result = builder().setExe(delegate).buildResult();

    assertEquals(
        ImmutableList.of(result.pathOf(delegate).toString()),
        result.aliasBinary().getExecutableCommand().getCommandPrefix(result.sourcePathResolver()));
  }

  @Test
  public void supportsStringArgs() throws NoSuchBuildTargetException {
    String[] args = {"a", "b c", "def"};
    CommandAliasBuilder.BuildResult result =
        builder().setExe(delegate).setStringArgs(args).buildResult();

    ImmutableList<String> commandPrefix =
        result.aliasBinary().getExecutableCommand().getCommandPrefix(result.sourcePathResolver());
    assertEquals(ImmutableList.copyOf(args), commandPrefix.subList(1, commandPrefix.size()));
  }

  @Test
  public void supportsLocationMacrosInArgs() throws NoSuchBuildTargetException {
    CommandAliasBuilder.BuildResult result =
        builder()
            .setExe(delegate)
            .setMacroArg("prefix %s suffix", LocationMacro.of(argTarget))
            .addTarget(argTarget)
            .buildResult();

    ImmutableList<String> commandPrefix =
        result.aliasBinary().getExecutableCommand().getCommandPrefix(result.sourcePathResolver());
    assertEquals(
        ImmutableList.of(
            String.format(
                "prefix %s suffix",
                result
                    .sourcePathResolver()
                    .getAbsolutePath(
                        result.resolver().requireRule(argTarget).getSourcePathToOutput()))),
        commandPrefix.subList(1, commandPrefix.size()));
    assertThat(
        argTarget,
        in(result.aliasBinary().getRuntimeDeps(result.ruleFinder()).collect(Collectors.toList())));
  }

  @Test
  public void addsMacrosToParseDeps() {
    TargetNode<CommandAliasDescriptionArg, CommandAliasDescription> targetNode =
        builder()
            .setExe(delegate)
            .setMacroArg("prefix %s suffix", LocationMacro.of(argTarget))
            .addTarget(argTarget)
            .build();

    assertEquals(ImmutableSet.of(delegate, argTarget), targetNode.getParseDeps());
  }

  @Test
  public void supportsStringEnvVars() throws NoSuchBuildTargetException {
    String key = "THE_KEY";
    String value = "arbitrary value";
    CommandAliasBuilder.BuildResult result =
        builder().setExe(delegate).setStringEnv(key, value).buildResult();

    assertEquals(
        ImmutableMap.of(key, value),
        result.aliasBinary().getExecutableCommand().getEnvironment(result.sourcePathResolver()));
  }

  @Test
  public void supportsLocationMacrosInEnv() throws NoSuchBuildTargetException {
    String key = "THE_KEY";
    CommandAliasBuilder.BuildResult result =
        builder()
            .setExe(delegate)
            .setMacroEnv(key, "prefix %s suffix", LocationMacro.of(argTarget))
            .addTarget(argTarget)
            .buildResult();

    ImmutableMap<String, String> env =
        result.aliasBinary().getExecutableCommand().getEnvironment(result.sourcePathResolver());
    assertEquals(
        ImmutableMap.of(
            key,
            String.format(
                "prefix %s suffix",
                result
                    .sourcePathResolver()
                    .getAbsolutePath(
                        result.resolver().requireRule(argTarget).getSourcePathToOutput()))),
        env);
    assertThat(
        argTarget,
        in(result.aliasBinary().getRuntimeDeps(result.ruleFinder()).collect(Collectors.toList())));
  }

  @Test
  public void supportsWrappingOtherAliasBinaries() throws NoSuchBuildTargetException {
    CommandAliasBuilder builder = builder();

    BuildTarget innerCommand = BuildTargetFactory.newInstance("//inner:command");
    TargetNode<?, ?> otherAlias =
        builder.subBuilder(delegate).setExe(innerCommand).setStringArgs("ab", "cd").build();

    CommandAliasBuilder.BuildResult result =
        builder.setExe(delegate, otherAlias).setStringArgs("d e f").buildResult();

    assertEquals(
        result.aliasBinary().getExecutableCommand().getCommandPrefix(result.sourcePathResolver()),
        ImmutableList.of(result.pathOf(innerCommand).toString(), "ab", "cd", "d e f"));
  }

  private static CommandAliasBuilder builder() {
    return new CommandAliasBuilder(aliasBinaryTarget);
  }
}
