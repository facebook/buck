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
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeBinaryBuildRuleBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.UncachedRuleKeyBuilder;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class CommandAliasDescriptionTest {
  private static final BuildTarget commandAliasTarget =
      BuildTargetFactory.newInstance("//arbitrary:alias");
  private static final BuildTarget delegate =
      BuildTargetFactory.newInstance("//wrapped/command/to:run");
  private static final BuildTarget argTarget = BuildTargetFactory.newInstance("//macro:dependency");

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void runsBuildTargetsAsCommand() {
    CommandAliasBuilder.BuildResult result = builder().setExe(delegate).buildResult();

    assertEquals(
        ImmutableList.of(result.pathOf(delegate).toString()),
        result.commandAlias().getExecutableCommand().getCommandPrefix(result.sourcePathResolver()));
    assertThat(
        delegate,
        in(result.commandAlias().getRuntimeDeps(result.ruleFinder()).collect(Collectors.toList())));
  }

  @Test
  public void exposesTransitiveRuntimeDeps() {
    CommandAliasBuilder builder = builder();

    BuildTarget innerTarget = BuildTargetFactory.newInstance("//inner:tool");
    TargetNode<?, ?> innerNode =
        new ExportFileBuilder(innerTarget).setSrc(new FakeSourcePath("fake/path")).build();

    CommandAliasBuilder.BuildResult result =
        builder.setExe(builder.subBuilder(delegate).setExe(innerNode).build()).buildResult();

    assertThat(
        innerTarget,
        in(result.commandAlias().getRuntimeDeps(result.ruleFinder()).collect(Collectors.toList())));
  }

  @Test
  public void onlyExposesRuntimeDepsOfTheActualPlatform() {
    BuildTarget genericExe = BuildTargetFactory.newInstance("//exe:generic");
    BuildTarget linuxExe = BuildTargetFactory.newInstance("//exe:linux");

    Function<Platform, Stream<BuildTarget>> runtimeDeps =
        platform -> {
          CommandAliasBuilder.BuildResult result =
              builder(platform)
                  .setExe(genericExe)
                  .setPlatformExe(ImmutableMap.of(Platform.LINUX, linuxExe))
                  .buildResult();
          return result.commandAlias().getRuntimeDeps(result.ruleFinder());
        };

    List<BuildTarget> genericRuntimeDeps =
        runtimeDeps.apply(Platform.UNKNOWN).collect(Collectors.toList());
    assertThat(genericExe, in(genericRuntimeDeps));
    assertThat(linuxExe, not(in(genericRuntimeDeps)));

    List<BuildTarget> linuxRuntimeDeps =
        runtimeDeps.apply(Platform.LINUX).collect(Collectors.toList());
    assertThat(linuxExe, in(linuxRuntimeDeps));
    assertThat(genericExe, not(in(linuxRuntimeDeps)));
  }

  @Test
  public void supportsStringArgs() {
    String[] args = {"a", "b c", "def"};
    CommandAliasBuilder.BuildResult result =
        builder().setExe(delegate).setStringArgs(args).buildResult();

    ImmutableList<String> commandPrefix =
        result.commandAlias().getExecutableCommand().getCommandPrefix(result.sourcePathResolver());
    assertEquals(ImmutableList.copyOf(args), commandPrefix.subList(1, commandPrefix.size()));
  }

  @Test
  public void supportsLocationMacrosInArgs() {
    CommandAliasBuilder.BuildResult result =
        builder()
            .setExe(delegate)
            .setMacroArg("prefix %s suffix", LocationMacro.of(argTarget))
            .addTarget(argTarget)
            .buildResult();

    ImmutableList<String> commandPrefix =
        result.commandAlias().getExecutableCommand().getCommandPrefix(result.sourcePathResolver());
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
        in(result.commandAlias().getRuntimeDeps(result.ruleFinder()).collect(Collectors.toList())));
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
  public void supportsStringEnvVars() {
    String key = "THE_KEY";
    String value = "arbitrary value";
    CommandAliasBuilder.BuildResult result =
        builder().setExe(delegate).setStringEnv(key, value).buildResult();

    assertEquals(
        ImmutableMap.of(key, value),
        result.commandAlias().getExecutableCommand().getEnvironment(result.sourcePathResolver()));
  }

  @Test
  public void supportsLocationMacrosInEnv() {
    String key = "THE_KEY";
    CommandAliasBuilder.BuildResult result =
        builder()
            .setExe(delegate)
            .setMacroEnv(key, "prefix %s suffix", LocationMacro.of(argTarget))
            .addTarget(argTarget)
            .buildResult();

    ImmutableMap<String, String> env =
        result.commandAlias().getExecutableCommand().getEnvironment(result.sourcePathResolver());
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
        in(result.commandAlias().getRuntimeDeps(result.ruleFinder()).collect(Collectors.toList())));
  }

  @Test
  public void supportsWrappingOtherAliasBinaries() {
    CommandAliasBuilder builder = builder();

    BuildTarget innerCommand = BuildTargetFactory.newInstance("//inner:command");
    TargetNode<?, ?> otherAlias =
        builder.subBuilder(delegate).setExe(innerCommand).setStringArgs("ab", "cd").build();

    CommandAliasBuilder.BuildResult result =
        builder.setExe(otherAlias).setStringArgs("d e f").buildResult();

    assertEquals(
        result.commandAlias().getExecutableCommand().getCommandPrefix(result.sourcePathResolver()),
        ImmutableList.of(result.pathOf(innerCommand).toString(), "ab", "cd", "d e f"));
  }

  @Test
  public void supportsPlatformSpecificExecutables() {
    Platform platform = Platform.FREEBSD;
    CommandAliasBuilder builder = builder(platform);

    BuildTarget forOtherPlatforms = BuildTargetFactory.newInstance("//for/other:platforms");

    CommandAliasBuilder.BuildResult result =
        builder
            .setExe(forOtherPlatforms)
            .setPlatformExe(ImmutableSortedMap.of(platform, delegate))
            .buildResult();

    assertEquals(
        ImmutableList.of(result.pathOf(delegate).toString()),
        result.commandAlias().getExecutableCommand().getCommandPrefix(result.sourcePathResolver()));
  }

  @Test
  public void exeCanBeOmitted() {
    CommandAliasBuilder.BuildResult result =
        builder(Platform.FREEBSD)
            .setPlatformExe(ImmutableSortedMap.of(Platform.FREEBSD, delegate))
            .buildResult();

    assertEquals(
        ImmutableList.of(result.pathOf(delegate).toString()),
        result.commandAlias().getExecutableCommand().getCommandPrefix(result.sourcePathResolver()));
  }

  @Test
  public void throwsLateForIfPlatformNotProvided() {
    CommandAliasBuilder.BuildResult result =
        builder(Platform.WINDOWS)
            .setPlatformExe(ImmutableSortedMap.of(Platform.LINUX, delegate))
            .buildResult();

    exception.expect(HumanReadableException.class);
    result.commandAlias().getExecutableCommand().getCommandPrefix(result.sourcePathResolver());
  }

  @Test
  public void eitherExeOrPlatformExeMustBePresent() {
    exception.expect(HumanReadableException.class);
    builder()
        .buildResult()
        .commandAlias()
        .getExecutableCommand()
        .getCommandPrefix(builder().buildResult().sourcePathResolver());
  }

  @Test
  public void supportsArgsAndEnvForPlatorms() {
    CommandAliasBuilder builder = builder(Platform.WINDOWS);

    BuildTarget innerExe = BuildTargetFactory.newInstance("//sub:command");
    String[] args = {"ab", "cd"};
    TargetNode<?, ?> subCommand =
        builder
            .subBuilder(delegate)
            .setExe(innerExe)
            .setStringArgs(args)
            .setStringEnv("EF", "gh")
            .build();
    CommandAliasBuilder.BuildResult result =
        builder.setPlatformExe(Platform.WINDOWS, subCommand).buildResult();

    Tool tool = result.commandAlias().getExecutableCommand();
    ImmutableList<String> commandPrefix = tool.getCommandPrefix(result.sourcePathResolver());
    assertEquals(commandPrefix.subList(1, commandPrefix.size()), ImmutableList.copyOf(args));
    assertEquals(tool.getEnvironment(result.sourcePathResolver()), ImmutableMap.of("EF", "gh"));
  }

  @Test
  public void exposesDepsOfTheUsedTool() {
    BiConsumer<Platform, String[]> test =
        (platform, deps) -> {
          CommandAliasBuilder.BuildResult result = multiPlatformScenario(platform);
          assertEquals(
              Stream.of(deps)
                  .map(BuildTargetFactory::newInstance)
                  .map(result.resolver()::getRule)
                  .collect(Collectors.toSet()),
              result.commandAlias().getExecutableCommand().getDeps(result.ruleFinder()));
        };

    test.accept(Platform.FREEBSD, new String[] {"//a:dep1", "//b:dep1"});
    test.accept(Platform.UNKNOWN, new String[] {"//a:dep0", "//b:dep0"});
  }

  @Test
  public void exposesInputsOfTheUsedTool() {
    BiConsumer<Platform, String[]> test =
        (platform, inputs) -> {
          CommandAliasBuilder.BuildResult result = multiPlatformScenario(platform);
          assertEquals(
              Stream.of(inputs).map(FakeSourcePath::new).collect(Collectors.toSet()),
              result.commandAlias().getExecutableCommand().getInputs());
        };

    test.accept(Platform.LINUX, new String[] {"a2", "b2"});
    test.accept(Platform.UNKNOWN, new String[] {"a0", "b0"});
  }

  @Test
  public void platformSpecificExecutablesAffectRuleKey() {
    BuildTarget forOtherPlatforms = BuildTargetFactory.newInstance("//for/other:platforms");
    CommandAliasBuilder.BuildResult platformSpecific =
        builder(Platform.LINUX)
            .setExe(forOtherPlatforms)
            .setPlatformExe(
                ImmutableSortedMap.of(Platform.FREEBSD, delegate, Platform.MACOS, delegate))
            .buildResult();
    CommandAliasBuilder.BuildResult simple =
        builder(Platform.LINUX).setExe(forOtherPlatforms).buildResult();

    assertNotEquals(ruleKey(simple), ruleKey(platformSpecific));
  }

  @Test
  public void differentConfigurationsChangeRuleKey() {
    CommandAliasBuilder.BuildResult one =
        builder()
            .setPlatformExe(
                ImmutableSortedMap.of(Platform.FREEBSD, delegate, Platform.MACOS, delegate))
            .buildResult();

    BuildTarget secondExe = BuildTargetFactory.newInstance("//other:target");
    CommandAliasBuilder.BuildResult two =
        builder()
            .setPlatformExe(
                ImmutableSortedMap.of(Platform.FREEBSD, secondExe, Platform.MACOS, secondExe))
            .buildResult();

    assertNotEquals(ruleKey(one), ruleKey(two));
  }

  @Test
  public void runtimePlatformIsIrrelevantForRuleKey() {
    BuildTarget windowsTarget = BuildTargetFactory.newInstance("//target/for:windows");
    BuildTarget macosTarget = BuildTargetFactory.newInstance("//target/for:macos");

    ImmutableSortedMap<Platform, BuildTarget> platformExe =
        ImmutableSortedMap.of(Platform.WINDOWS, windowsTarget, Platform.MACOS, macosTarget);

    List<RuleKey> ruleKeys = new ArrayList<>(3);
    Platform[] platforms = {Platform.LINUX, Platform.WINDOWS, Platform.MACOS};
    for (Platform platform : platforms) {
      ruleKeys.add(
          ruleKey(builder(platform).setExe(delegate).setPlatformExe(platformExe).buildResult()));
    }

    assertEquals(ruleKeys.get(0), ruleKeys.get(1));
    assertEquals(ruleKeys.get(0), ruleKeys.get(2));
  }

  private static CommandAliasBuilder builder() {
    return new CommandAliasBuilder(commandAliasTarget);
  }

  private static CommandAliasBuilder builder(Platform platform) {
    return new CommandAliasBuilder(commandAliasTarget, new CommandAliasDescription(platform));
  }

  private RuleKey ruleKey(CommandAliasBuilder.BuildResult result) {
    SourcePathResolver pathResolver = result.sourcePathResolver();
    SourcePathRuleFinder ruleFinder = result.ruleFinder();
    FakeFileHashCache hashCache = FakeFileHashCache.createFromStrings(ImmutableMap.of());
    return new UncachedRuleKeyBuilder(
            ruleFinder,
            pathResolver,
            hashCache,
            ruleKeyFactory(hashCache, pathResolver, ruleFinder))
        .setReflectively("key", result.commandAlias())
        .build(RuleKey::new);
  }

  private DefaultRuleKeyFactory ruleKeyFactory(
      FileHashCache hashCache, SourcePathResolver pathResolver, SourcePathRuleFinder ruleFinder) {
    return new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder);
  }

  private static CommandAliasBuilder.BuildResult multiPlatformScenario(Platform platform) {
    CommandAliasBuilder builder = builder(platform);
    TargetNode<?, ?>[] exes = new TargetNode<?, ?>[3];
    for (int i = 0; i < exes.length; i++) {
      String index = String.valueOf(i);
      exes[i] =
          new FakeBinaryBuildRuleBuilder(BuildTargetFactory.newInstance("//sh:bin" + index))
              .setDeps(
                  ImmutableSortedSet.of(
                      builder.addBuildRule(BuildTargetFactory.newInstance("//a:dep" + index)),
                      builder.addBuildRule(BuildTargetFactory.newInstance("//b:dep" + index))))
              .setInputs(
                  ImmutableSortedSet.of(
                      new FakeSourcePath("a" + index), new FakeSourcePath("b" + index)))
              .build();
    }

    return builder
        .setExe(exes[0])
        .setPlatformExe(Platform.FREEBSD, exes[1])
        .setPlatformExe(Platform.LINUX, exes[2])
        .buildResult();
  }
}
