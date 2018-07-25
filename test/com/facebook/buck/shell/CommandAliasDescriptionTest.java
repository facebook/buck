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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.UncachedRuleKeyBuilder;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.function.Function;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class CommandAliasDescriptionTest {
  private static final BuildTarget commandAliasTarget =
      BuildTargetFactory.newInstance("//arbitrary:alias");
  private static final BuildTarget delegate =
      BuildTargetFactory.newInstance("//wrapped/command/to:run");
  private static final BuildTarget macroTarget =
      BuildTargetFactory.newInstance("//macro:dependency");

  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void hasNoBuildDeps() {
    assertEquals(ImmutableSortedSet.of(), simpleSetup().commandAlias().getBuildDeps());
  }

  @Test
  public void hasNoBuildSteps() {
    CommandAliasBuilder.BuildResult setup = simpleSetup();
    assertEquals(
        ImmutableList.of(),
        setup
            .commandAlias()
            .getBuildSteps(
                FakeBuildContext.withSourcePathResolver(setup.sourcePathResolver()),
                new FakeBuildableContext()));
  }

  @Test
  public void runsBuildRulesAsCommand() {
    CommandAliasBuilder.BuildResult result = builder().setExe(delegate).buildResult();

    assertThat(result.getCommandPrefix(), equalTo(ImmutableList.of(result.pathOf(delegate))));
    assertThat(result.getRuntimeDeps(), hasItem(CommandAliasDescriptionTest.delegate));
  }

  @Test
  public void runsBinaryBuildRulesAsCommand() {
    CommandAliasBuilder.BuildResult result =
        builder()
            .setExe(new ShBinaryBuilder(delegate).setMain(FakeSourcePath.of("sh/binary")).build())
            .buildResult();

    assertThat(
        result.getCommandPrefix(),
        equalTo(
            result
                .graphBuilder()
                .getRuleWithType(delegate, BinaryBuildRule.class)
                .getExecutableCommand()
                .getCommandPrefix(result.sourcePathResolver())));
    assertThat(result.getRuntimeDeps(), hasItem(delegate));
  }

  @Test
  public void supportsArgsWithNonBinaryBuildRules() {
    StringWithMacros[] args = {
      StringWithMacrosUtils.format("apples"),
      StringWithMacrosUtils.format("pears=%s", LocationMacro.of(macroTarget))
    };
    CommandAliasBuilder.BuildResult result = builder().setExe(delegate).setArgs(args).buildResult();

    assertThat(
        result.getCommandPrefix(),
        equalTo(
            ImmutableList.of(
                result.pathOf(delegate),
                "apples",
                String.format("pears=%s", result.pathOf(macroTarget)))));
    assertThat(result.getRuntimeDeps(), hasItem(macroTarget));
  }

  @Test
  public void supportsArgsWithBinaryBuildRules() {
    StringWithMacros[] args = {
      StringWithMacrosUtils.format("apples"),
      StringWithMacrosUtils.format("pears=%s", LocationMacro.of(macroTarget))
    };

    CommandAliasBuilder.BuildResult result =
        builder()
            .setExe(new ShBinaryBuilder(delegate).setMain(FakeSourcePath.of("sh/binary")).build())
            .setArgs(args)
            .buildResult();

    assertThat(
        result.getCommandPrefix(),
        equalTo(
            ImmutableList.builder()
                .addAll(result.exeOf(delegate))
                .add("apples", String.format("pears=%s", result.pathOf(macroTarget)))
                .build()));
    assertThat(result.getRuntimeDeps(), hasItem(macroTarget));
  }

  @Test
  public void supportsEnvWithNonBinaryBuildRules() {
    ImmutableMap<String, StringWithMacros> env =
        ImmutableSortedMap.of(
            "apples", StringWithMacrosUtils.format("some"),
            "pears", StringWithMacrosUtils.format("%s", LocationMacro.of(macroTarget)));

    CommandAliasBuilder.BuildResult result = builder().setExe(delegate).setEnv(env).buildResult();

    assertThat(
        result.getEnvironment(),
        equalTo(ImmutableSortedMap.of("apples", "some", "pears", result.pathOf(macroTarget))));
    assertThat(result.getRuntimeDeps(), hasItem(macroTarget));
  }

  @Test
  public void supportsEnvWithBinaryBuildRules() {
    ImmutableMap<String, StringWithMacros> env =
        ImmutableSortedMap.of(
            "apples", StringWithMacrosUtils.format("some"),
            "pears", StringWithMacrosUtils.format("%s", LocationMacro.of(macroTarget)));

    CommandAliasBuilder.BuildResult result =
        builder()
            .setExe(new ShBinaryBuilder(delegate).setMain(FakeSourcePath.of("sh/binary")).build())
            .setEnv(env)
            .buildResult();

    assertThat(
        result.getEnvironment(),
        equalTo(ImmutableSortedMap.of("apples", "some", "pears", result.pathOf(macroTarget))));
    assertThat(result.getRuntimeDeps(), hasItem(macroTarget));
  }

  @Test
  public void addsMacrosToParseDeps() {
    BuildTarget envTarget = BuildTargetFactory.newInstance("//:for-env");
    TargetNode<CommandAliasDescriptionArg> targetNode =
        builder()
            .setExe(delegate)
            .setArgs(
                StringWithMacrosUtils.format("prefix %s suffix", LocationMacro.of(macroTarget)))
            .setEnv(
                ImmutableMap.of(
                    "arbitrary", StringWithMacrosUtils.format("%s", LocationMacro.of(envTarget))))
            .build();

    assertThat(targetNode.getParseDeps(), hasItems(delegate, macroTarget, envTarget));
  }

  @Test
  public void supportsConcatenatedArgs() {
    CommandAliasBuilder builder = builder();

    StringWithMacros[] delegateArgs = {
      StringWithMacrosUtils.format("apples"),
      StringWithMacrosUtils.format("pears=%s", LocationMacro.of(macroTarget))
    };
    BuildTarget innerCommand = BuildTargetFactory.newInstance("//inner:command");
    TargetNode<?> innerCommandAlias =
        builder.subBuilder(delegate).setExe(innerCommand).setArgs(delegateArgs).build();

    CommandAliasBuilder.BuildResult result =
        builder.setExe(innerCommandAlias).setStringArgs("bananas").buildResult();

    assertThat(
        result.getCommandPrefix(),
        equalTo(ImmutableList.builder().addAll(result.exeOf(delegate)).add("bananas").build()));
    assertThat(result.getRuntimeDeps(), hasItems(delegate, macroTarget));
  }

  @Test
  public void supportsConcatenatedEnvs() {
    CommandAliasBuilder builder = builder();

    ImmutableMap<String, StringWithMacros> delegateEnv =
        ImmutableSortedMap.of(
            "apples", StringWithMacrosUtils.format("some"),
            "pears", StringWithMacrosUtils.format("%s", LocationMacro.of(macroTarget)));
    BuildTarget innerCommand = BuildTargetFactory.newInstance("//inner:command");
    TargetNode<?> innerCommandAlias =
        builder.subBuilder(delegate).setExe(innerCommand).setEnv(delegateEnv).build();

    CommandAliasBuilder.BuildResult result =
        builder
            .setExe(innerCommandAlias)
            .setEnv(ImmutableMap.of("bananas", StringWithMacrosUtils.format("none")))
            .buildResult();

    assertThat(
        result.getEnvironment(),
        equalTo(
            ImmutableMap.of(
                "apples", "some", "pears", result.pathOf(macroTarget), "bananas", "none")));
    assertThat(result.getRuntimeDeps(), hasItems(delegate, macroTarget));
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

    assertEquals(ImmutableList.of(result.pathOf(delegate)), result.getCommandPrefix());
  }

  @Test
  public void exeCanBeOmitted() {
    CommandAliasBuilder.BuildResult result =
        builder(Platform.FREEBSD)
            .setPlatformExe(ImmutableSortedMap.of(Platform.FREEBSD, delegate))
            .buildResult();

    assertEquals(ImmutableList.of(result.pathOf(delegate)), result.getCommandPrefix());
  }

  @Test
  public void throwsLateForIfPlatformNotProvided() {
    CommandAliasBuilder.BuildResult result =
        builder(Platform.WINDOWS)
            .setPlatformExe(ImmutableSortedMap.of(Platform.LINUX, delegate))
            .buildResult();

    exception.expect(HumanReadableException.class);
    result.getCommandPrefix();
  }

  @Test
  public void eitherExeOrPlatformExeMustBePresent() {
    exception.expect(HumanReadableException.class);
    builder().buildResult().getCommandPrefix();
  }

  @Test
  public void onlyExposesRuntimeDepsOfTheActualPlatform() {
    BuildTarget genericExe = BuildTargetFactory.newInstance("//exe:generic");
    BuildTarget linuxExe = BuildTargetFactory.newInstance("//exe:linux");

    Function<Platform, Iterable<BuildTarget>> runtimeDeps =
        platform -> {
          CommandAliasBuilder.BuildResult result =
              builder(platform)
                  .setExe(genericExe)
                  .setPlatformExe(ImmutableMap.of(Platform.LINUX, linuxExe))
                  .buildResult();
          return result.getRuntimeDeps();
        };

    Iterable<BuildTarget> genericRuntimeDeps = runtimeDeps.apply(Platform.UNKNOWN);
    assertThat(genericRuntimeDeps, hasItem(genericExe));
    assertThat(genericRuntimeDeps, not(hasItem(linuxExe)));

    Iterable<BuildTarget> linuxRuntimeDeps = runtimeDeps.apply(Platform.LINUX);
    assertThat(linuxRuntimeDeps, hasItem(linuxExe));
    assertThat(linuxRuntimeDeps, not(hasItem(genericExe)));
  }

  @Test
  public void supportsArgsAndEnvForPlatorms() {
    CommandAliasBuilder builder = builder(Platform.WINDOWS);

    BuildTarget innerExe = BuildTargetFactory.newInstance("//sub:command");
    String[] args = {"ab", "cd"};
    TargetNode<?> subCommand =
        builder
            .subBuilder(delegate)
            .setExe(innerExe)
            .setStringArgs(args)
            .setEnv(ImmutableMap.of("EF", StringWithMacrosUtils.format("gh")))
            .build();
    CommandAliasBuilder.BuildResult result =
        builder.setPlatformExe(Platform.WINDOWS, subCommand).buildResult();

    Tool tool = result.commandAlias().getExecutableCommand();
    ImmutableList<String> commandPrefix = tool.getCommandPrefix(result.sourcePathResolver());
    assertEquals(commandPrefix.subList(1, commandPrefix.size()), ImmutableList.copyOf(args));
    assertEquals(tool.getEnvironment(result.sourcePathResolver()), ImmutableMap.of("EF", "gh"));
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
                ImmutableSortedMap.of(Platform.FREEBSD, delegate, Platform.UNKNOWN, delegate))
            .buildResult();

    BuildTarget secondExe = BuildTargetFactory.newInstance("//other:target");
    CommandAliasBuilder.BuildResult two =
        builder()
            .setPlatformExe(
                ImmutableSortedMap.of(Platform.FREEBSD, secondExe, Platform.UNKNOWN, secondExe))
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

  @Test
  public void ruleKeyForToolIsStableAcrossPlatforms() {
    ImmutableMap<Platform, BuildTarget> platformExe =
        ImmutableMap.of(
            Platform.WINDOWS,
            BuildTargetFactory.newInstance("//:windows"),
            Platform.LINUX,
            BuildTargetFactory.newInstance("//:linux"));
    Function<Platform, RuleKey> ruleKeyForPlatform =
        platform -> {
          CommandAliasBuilder.BuildResult result =
              builder(platform).setPlatformExe(platformExe).buildResult();
          return ruleKey(result, result.commandAlias().getExecutableCommand());
        };

    RuleKey windows = ruleKeyForPlatform.apply(Platform.WINDOWS);
    RuleKey linux = ruleKeyForPlatform.apply(Platform.LINUX);
    RuleKey macos = ruleKeyForPlatform.apply(Platform.MACOS);

    assertEquals(windows, linux);
    assertEquals(windows, macos);
  }

  @Test
  public void underlyingCommandPrefixAffectsToolRulekey() {
    Function<String, RuleKey> ruleKeyForArg =
        arg -> {
          CommandAliasBuilder.BuildResult result =
              builder().setExe(new TestBinaryBuilder(delegate, arg, "").build()).buildResult();

          return ruleKey(result, result.commandAlias().getExecutableCommand());
        };

    assertNotEquals(ruleKeyForArg.apply("abc"), ruleKeyForArg.apply("def"));
  }

  @Test
  public void underlyingEnvironmentAffectsToolRulekey() {
    Function<String, RuleKey> ruleKeyForEnv =
        env -> {
          CommandAliasBuilder.BuildResult result =
              builder().setExe(new TestBinaryBuilder(delegate, "", env).build()).buildResult();

          return ruleKey(result, result.commandAlias().getExecutableCommand());
        };

    assertNotEquals(ruleKeyForEnv.apply("abc"), ruleKeyForEnv.apply("def"));
  }

  @Test
  public void underlyingCommandPrefixOfOtherPlatformAffectsToolRulekey() {
    BuildTarget unused = BuildTargetFactory.newInstance("//:unused");
    Function<String, RuleKey> ruleKeyForArg =
        arg -> {
          CommandAliasBuilder.BuildResult result =
              builder(Platform.FREEBSD)
                  .setExe(delegate)
                  .setPlatformExe(Platform.MACOS, new TestBinaryBuilder(unused, arg, "").build())
                  .buildResult();

          return ruleKey(result, result.commandAlias().getExecutableCommand());
        };

    assertNotEquals(ruleKeyForArg.apply("abc"), ruleKeyForArg.apply("def"));
  }

  @Test
  public void underlyingEnvironmentOfPlatformSpecificToolAffectsToolRulekey() {
    Function<String, RuleKey> ruleKeyForEnv =
        env -> {
          CommandAliasBuilder.BuildResult result =
              builder(Platform.LINUX)
                  .setPlatformExe(Platform.LINUX, new TestBinaryBuilder(delegate, "", env).build())
                  .buildResult();

          return ruleKey(result, result.commandAlias().getExecutableCommand());
        };

    assertNotEquals(ruleKeyForEnv.apply("abc"), ruleKeyForEnv.apply("def"));
  }

  @Test
  public void underlyingCommandPrefixOfPlatformSpecificToolAffectsToolRulekey() {
    Function<String, RuleKey> ruleKeyForArg =
        arg -> {
          CommandAliasBuilder.BuildResult result =
              builder(Platform.UNKNOWN)
                  .setPlatformExe(
                      Platform.UNKNOWN, new TestBinaryBuilder(delegate, arg, "").build())
                  .buildResult();

          return ruleKey(result, result.commandAlias().getExecutableCommand());
        };

    assertNotEquals(ruleKeyForArg.apply("abc"), ruleKeyForArg.apply("def"));
  }

  @Test
  public void underlyingEnvironmentOfOtherPlatformAffectsToolRulekey() {
    BuildTarget unused = BuildTargetFactory.newInstance("//:unused");
    Function<String, RuleKey> ruleKeyForEnv =
        env -> {
          CommandAliasBuilder.BuildResult result =
              builder(Platform.FREEBSD)
                  .setExe(delegate)
                  .setPlatformExe(Platform.MACOS, new TestBinaryBuilder(unused, "", env).build())
                  .buildResult();

          return ruleKey(result, result.commandAlias().getExecutableCommand());
        };

    assertNotEquals(ruleKeyForEnv.apply("abc"), ruleKeyForEnv.apply("def"));
  }

  private static CommandAliasBuilder builder() {
    return new CommandAliasBuilder(commandAliasTarget);
  }

  private static CommandAliasBuilder builder(Platform platform) {
    return new CommandAliasBuilder(commandAliasTarget, new CommandAliasDescription(platform));
  }

  private static CommandAliasBuilder.BuildResult simpleSetup() {
    return builder().setExe(delegate).buildResult();
  }

  private static RuleKey ruleKey(CommandAliasBuilder.BuildResult result) {
    return ruleKey(result, result.commandAlias());
  }

  private static RuleKey ruleKey(CommandAliasBuilder.BuildResult result, Object value) {
    SourcePathResolver pathResolver = result.sourcePathResolver();
    SourcePathRuleFinder ruleFinder = result.ruleFinder();
    FakeFileHashCache hashCache = FakeFileHashCache.createFromStrings(ImmutableMap.of());
    return new UncachedRuleKeyBuilder(
            ruleFinder,
            pathResolver,
            hashCache,
            new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder))
        .setReflectively("key", value)
        .build(RuleKey::new);
  }

  private static class TestBinary extends NoopBuildRule implements BinaryBuildRule {
    private final Tool tool;

    public TestBinary(BuildTarget buildTarget, ProjectFilesystem projectFilesystem, Tool tool) {
      super(buildTarget, projectFilesystem);
      this.tool = tool;
    }

    @Override
    public Tool getExecutableCommand() {
      return tool;
    }

    @Override
    public SortedSet<BuildRule> getBuildDeps() {
      return ImmutableSortedSet.of();
    }
  }

  // reuses CommandAliasDescriptionArg to avoid generating another Arg class with builder
  private static class TestBinaryDescription
      implements DescriptionWithTargetGraph<CommandAliasDescriptionArg> {
    private final String arg;
    private final String env;

    public TestBinaryDescription(String arg, String env) {
      this.arg = arg;
      this.env = env;
    }

    @Override
    public Class<CommandAliasDescriptionArg> getConstructorArgType() {
      return CommandAliasDescriptionArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget target,
        BuildRuleParams params,
        CommandAliasDescriptionArg args) {
      CommandTool tool = new CommandTool.Builder().addArg(arg).addEnv("env", env).build();
      return new TestBinary(target, context.getProjectFilesystem(), tool);
    }
  }

  private static class TestBinaryBuilder
      extends AbstractNodeBuilder<
          CommandAliasDescriptionArg.Builder,
          CommandAliasDescriptionArg,
          TestBinaryDescription,
          TestBinary> {
    public TestBinaryBuilder(BuildTarget target, String arg, String env) {
      super(new TestBinaryDescription(arg, env), target);
    }
  }
}
