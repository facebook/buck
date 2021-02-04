/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.rules.providers.lib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.artifact.SourceArtifactImpl;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.model.label.Label;
import com.facebook.buck.core.model.label.LabelSyntaxException;
import com.facebook.buck.core.rules.actions.ActionRegistryForTests;
import com.facebook.buck.core.rules.actions.lib.WriteAction;
import com.facebook.buck.core.rules.actions.lib.args.CommandLine;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgException;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgs;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgsFactory;
import com.facebook.buck.core.rules.actions.lib.args.ExecCompatibleCommandLineBuilder;
import com.facebook.buck.core.rules.providers.impl.UserDefinedProvider;
import com.facebook.buck.core.rules.providers.impl.UserDefinedProviderInfo;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.starlark.compatible.BuckStarlark;
import com.facebook.buck.core.starlark.compatible.TestMutableEnv;
import com.facebook.buck.core.starlark.rule.args.CommandLineArgsBuilder;
import com.facebook.buck.core.starlark.testutil.TestStarlarkParser;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Location;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RunInfoTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  private static StarlarkThread getEnv(Mutability mutability) {
    return new StarlarkThread(mutability, BuckStarlark.BUCK_STARLARK_SEMANTICS);
  }

  @Test
  public void errorOnInvalidEnvType() throws EvalException {

    try (Mutability mut = Mutability.create("test")) {
      Object env =
          Dict.copyOf(
              getEnv(mut).mutability(),
              ImmutableMap.of("foo", StarlarkInt.of(1), "bar", StarlarkInt.of(2)));
      thrown.expect(EvalException.class);
      // Broken cast, but this can apparently happen, so... verify :)
      RunInfo.instantiateFromSkylark((Dict<String, String>) env, ImmutableList.of("value"));
    }
  }

  @Test
  public void errorOnArgsType() throws EvalException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("must either be a list of arguments");

    RunInfo.instantiateFromSkylark(Dict.empty(), 1);
  }

  @Test
  public void errorsOnInvalidArgInListOfArgs() throws EvalException {
    thrown.expect(CommandLineArgException.class);
    thrown.expectMessage("Invalid command line argument type");

    RunInfo.instantiateFromSkylark(
        Dict.empty(),
        StarlarkList.immutableCopyOf(
            StarlarkList.immutableCopyOf(StarlarkList.immutableOf(Starlark.NONE))));
  }

  @Test
  public void handlesPartiallyBuiltCommandLineArgs() throws EvalException {
    CommandLineArgsBuilder builder =
        new CommandLineArgsBuilder()
            .add("foo", Starlark.NONE, CommandLineArgs.DEFAULT_FORMAT_STRING)
            .add("bar", Starlark.NONE, CommandLineArgs.DEFAULT_FORMAT_STRING);

    RunInfo runInfo = RunInfo.instantiateFromSkylark(Dict.empty(), builder);
    builder.add("baz", Starlark.NONE, CommandLineArgs.DEFAULT_FORMAT_STRING);

    CommandLine cli =
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(new FakeProjectFilesystem()))
            .build(runInfo.args());
    assertEquals(ImmutableList.of("foo", "bar"), cli.getCommandLineArgs());
  }

  @Test
  public void handlesCommandLineArgs() throws EvalException {
    CommandLineArgs args =
        new CommandLineArgsBuilder()
            .add("foo", Starlark.NONE, CommandLineArgs.DEFAULT_FORMAT_STRING)
            .add("bar", Starlark.NONE, CommandLineArgs.DEFAULT_FORMAT_STRING)
            .build();

    RunInfo runInfo = RunInfo.instantiateFromSkylark(Dict.empty(), args);

    CommandLine cli =
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(new FakeProjectFilesystem()))
            .build(runInfo.args());
    assertEquals(ImmutableList.of("foo", "bar"), cli.getCommandLineArgs());
  }

  @Test
  public void handlesListOfArgs() throws EvalException {
    RunInfo runInfo =
        RunInfo.instantiateFromSkylark(
            Dict.empty(), StarlarkList.immutableCopyOf(ImmutableList.of("foo", StarlarkInt.of(1))));

    CommandLine cli =
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(new FakeProjectFilesystem()))
            .build(runInfo.args());
    assertEquals(ImmutableList.of("foo", "1"), cli.getCommandLineArgs());
  }

  @Test
  public void usesDefaultSkylarkValues() throws Exception {
    Object raw =
        TestStarlarkParser.eval(
            "RunInfo()", ImmutableMap.of(RunInfo.PROVIDER.getName(), RunInfo.PROVIDER));
    assertTrue(raw instanceof RunInfo);
    RunInfo runInfo = (RunInfo) raw;

    CommandLine cli =
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(new FakeProjectFilesystem()))
            .build(runInfo.args());

    assertEquals(ImmutableList.of(), cli.getCommandLineArgs());
    assertEquals(ImmutableMap.of(), cli.getEnvironmentVariables());
  }

  @Test
  public void returnsCorrectCliArgsAndEnv() throws EvalException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path txtPath = Paths.get("subdir", "file.txt");
    try (Mutability mut = Mutability.create("test")) {
      Artifact artifact = SourceArtifactImpl.of(PathSourcePath.of(filesystem, txtPath));

      BuildTarget target = BuildTargetFactory.newInstance("//:some_rule");
      ActionRegistryForTests registry = new ActionRegistryForTests(target, filesystem);
      Artifact artifact2 = registry.declareArtifact(Paths.get("out.txt"), Location.BUILTIN);
      OutputArtifact artifact2Output = (OutputArtifact) artifact2.asSkylarkOutputArtifact();
      Path artifact2Path =
          BuildPaths.getGenDir(filesystem.getBuckPaths(), target).resolve("out.txt");

      StarlarkThread environment = getEnv(mut);
      Dict<String, String> env =
          Dict.copyOf(
              environment.mutability(), ImmutableMap.of("foo", "foo_val", "bar", "bar_val"));
      StarlarkList<Object> args =
          StarlarkList.of(
              environment.mutability(),
              CommandLineArgsFactory.from(ImmutableList.of("arg1", "arg2")),
              artifact,
              artifact2Output);

      RunInfo info = RunInfo.instantiateFromSkylark(env, args);

      // Make sure we're freezing properly
      args.addElement("arg3");
      env.pop("foo", "", environment);

      new WriteAction(
          registry,
          ImmutableSortedSet.of(),
          ImmutableSortedSet.of(artifact2Output),
          "contents",
          false);

      CommandLine cli =
          new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(filesystem)).build(info);

      assertEquals(
          ImmutableList.of("arg1", "arg2", txtPath.toString(), artifact2Path.toString()),
          cli.getCommandLineArgs());
      assertEquals(
          ImmutableMap.of("foo", "foo_val", "bar", "bar_val"), cli.getEnvironmentVariables());
      assertEquals(4, info.getEstimatedArgsCount());

      ImmutableList.Builder<Artifact> inputs = ImmutableList.builder();
      ImmutableList.Builder<OutputArtifact> outputs = ImmutableList.builder();
      info.visitInputsAndOutputs(inputs::add, outputs::add);

      assertEquals(ImmutableList.of(artifact), inputs.build());
      assertEquals(ImmutableList.of(artifact2Output), outputs.build());
    }
  }

  @Test
  public void isImmutable() throws LabelSyntaxException, EvalException, InterruptedException {
    CommandLineArgs args1 =
        CommandLineArgsFactory.from(
            ImmutableList.of(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3)));
    CommandLineArgs args = new ImmutableRunInfo(ImmutableMap.of(), args1);

    assertTrue(args.isImmutable());

    UserDefinedProvider provider = new UserDefinedProvider(Location.BUILTIN, new String[] {"foo"});
    provider.export(Label.parseAbsolute("//:foo.bzl", ImmutableMap.of()), "provider");
    try (TestMutableEnv env = new TestMutableEnv()) {
      UserDefinedProviderInfo providerInfo =
          (UserDefinedProviderInfo)
              Starlark.call(
                  env.getEnv(), provider, ImmutableList.of(), ImmutableMap.of("foo", args));
      assertEquals(args, providerInfo.getValue("foo"));
      assertTrue(providerInfo.isImmutable());
    }
  }
}
