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
import com.facebook.buck.core.artifact.ImmutableSourceArtifactImpl;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rules.actions.lib.args.CommandLine;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgException;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgs;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgsFactory;
import com.facebook.buck.core.rules.actions.lib.args.ExecCompatibleCommandLineBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.starlark.compatible.BuckStarlark;
import com.facebook.buck.core.starlark.rule.args.CommandLineArgsBuilder;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.Identifier;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RunInfoTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  private static Environment getEnv(Mutability mutability) {
    return Environment.builder(mutability)
        .setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS)
        .build();
  }

  @Test
  public void errorOnInvalidEnvType() throws EvalException {

    try (Mutability mut = Mutability.create("test")) {
      Object env = SkylarkDict.of(getEnv(mut), "foo", 1, "bar", 2);
      thrown.expect(EvalException.class);
      // Broken cast, but this can apparently happen, so... verify :)
      RunInfo.instantiateFromSkylark((SkylarkDict<String, String>) env, ImmutableList.of("value"));
    }
  }

  @Test
  public void errorOnArgsType() throws EvalException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("must either be a list of arguments");

    RunInfo.instantiateFromSkylark(SkylarkDict.empty(), 1);
  }

  @Test
  public void errorsOnInvalidArgInListOfArgs() throws EvalException {
    thrown.expect(CommandLineArgException.class);
    thrown.expectMessage("Invalid command line argument type");

    RunInfo.instantiateFromSkylark(
        SkylarkDict.empty(), SkylarkList.createImmutable(ImmutableList.of(ImmutableList.of(1))));
  }

  @Test
  public void handlesPartiallyBuiltCommandLineArgs() throws EvalException {
    CommandLineArgsBuilder builder =
        new CommandLineArgsBuilder()
            .add("foo", Runtime.UNBOUND, Location.BUILTIN)
            .add("bar", Runtime.UNBOUND, Location.BUILTIN);

    RunInfo runInfo = RunInfo.instantiateFromSkylark(SkylarkDict.empty(), builder);
    builder.add("baz", Runtime.UNBOUND, Location.BUILTIN);

    CommandLine cli =
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(new FakeProjectFilesystem()))
            .build(runInfo.args());
    assertEquals(ImmutableList.of("foo", "bar"), cli.getCommandLineArgs());
  }

  @Test
  public void handlesCommandLineArgs() throws EvalException {
    CommandLineArgs args =
        new CommandLineArgsBuilder()
            .add("foo", Runtime.UNBOUND, Location.BUILTIN)
            .add("bar", Runtime.UNBOUND, Location.BUILTIN)
            .build();

    RunInfo runInfo = RunInfo.instantiateFromSkylark(SkylarkDict.empty(), args);

    CommandLine cli =
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(new FakeProjectFilesystem()))
            .build(runInfo.args());
    assertEquals(ImmutableList.of("foo", "bar"), cli.getCommandLineArgs());
  }

  @Test
  public void handlesListOfArgs() throws EvalException {
    RunInfo runInfo =
        RunInfo.instantiateFromSkylark(
            SkylarkDict.empty(), SkylarkList.createImmutable(ImmutableList.of("foo", 1)));

    CommandLine cli =
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(new FakeProjectFilesystem()))
            .build(runInfo.args());
    assertEquals(ImmutableList.of("foo", "1"), cli.getCommandLineArgs());
  }

  @Test
  public void usesDefaultSkylarkValues() throws InterruptedException, EvalException {
    Mutability mutability = Mutability.create("providertest");
    Environment env =
        Environment.builder(mutability)
            .setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS)
            .setGlobals(
                Environment.GlobalFrame.createForBuiltins(
                    ImmutableMap.of(RunInfo.PROVIDER.getName(), RunInfo.PROVIDER)))
            .build();

    FuncallExpression ast = new FuncallExpression(new Identifier("RunInfo"), ImmutableList.of());

    Object raw = ast.eval(env);
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
      Artifact artifact = ImmutableSourceArtifactImpl.of(PathSourcePath.of(filesystem, txtPath));
      Environment environment = getEnv(mut);
      SkylarkDict<String, String> env =
          SkylarkDict.of(environment, "foo", "foo_val", "bar", "bar_val");
      SkylarkList.MutableList<Object> args =
          SkylarkList.MutableList.of(
              environment, CommandLineArgsFactory.from(ImmutableList.of("arg1", "arg2")), artifact);

      RunInfo info = RunInfo.instantiateFromSkylark(env, args);

      // Make sure we're freezing properly
      args.add("arg3", Location.BUILTIN, mut);
      env.pop("foo", "", Location.BUILTIN, environment);

      CommandLine cli =
          new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(filesystem)).build(info);

      assertEquals(ImmutableList.of("arg1", "arg2", txtPath.toString()), cli.getCommandLineArgs());
      assertEquals(
          ImmutableMap.of("foo", "foo_val", "bar", "bar_val"), cli.getEnvironmentVariables());
      assertEquals(3, info.getEstimatedArgsCount());
    }
  }
}
