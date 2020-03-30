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

package com.facebook.buck.core.rules.actions.lib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.artifact.SourceArtifactImpl;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgException;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgsFactory;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystem;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.impl.TestActionExecutionRunner;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.EvalException;
import com.sun.jna.Platform;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class RunActionTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private DefaultProjectFilesystem filesystem;
  private TestActionExecutionRunner runner;
  private Artifact script;
  private Path scriptPath;

  @Before
  public void setUp() throws IOException, EvalException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "run_scripts", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    runner = new TestActionExecutionRunner(filesystem, target);
    scriptPath = Platform.isWindows() ? Paths.get("script.bat") : Paths.get("script.sh");
    script = runner.declareArtifact(scriptPath);

    // Make sure we're testing w/ a BuildArtifact instead of a SourceArtifact
    runner.runAction(
        new WriteAction(
            runner.getRegistry(),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of(script.asOutputArtifact()),
            filesystem.readFileIfItExists(scriptPath).get(),
            true));
  }

  ImmutableList<String> getStderr(StepExecutionResult stderr) {
    return Splitter.on(System.lineSeparator()).splitToList(stderr.getStderr().get().trim()).stream()
        .map(String::trim)
        .collect(ImmutableList.toImmutableList());
  }

  @Test
  public void usesUserProvidedEnvIfPossible() throws CommandLineArgException, IOException {
    ImmutableList<String> expectedStderr =
        ImmutableList.of(
            "Message on stderr",
            "arg[--foo]",
            "arg[bar]",
            String.format("PWD: %s", filesystem.getRootPath().toString()),
            "CUSTOM_ENV: value");

    RunAction action =
        new RunAction(
            runner.getRegistry(),
            "list",
            CommandLineArgsFactory.from(ImmutableList.of(script, "--foo", "bar")),
            ImmutableMap.of("CUSTOM_ENV", "value"));

    StepExecutionResult result = runner.runAction(action).getResult();
    assertTrue(result.isSuccess());
    assertEquals(expectedStderr, getStderr(result));
  }

  @Test
  public void returnsErrorOnZeroArgs() throws IOException {
    RunAction action =
        new RunAction(
            runner.getRegistry(),
            "list",
            CommandLineArgsFactory.from(ImmutableList.of()),
            ImmutableMap.of());

    StepExecutionResult result = runner.runAction(action).getResult();
    assertFalse(result.isSuccess());
  }

  @Test
  public void returnsOutputOnSuccess() throws CommandLineArgException, IOException {

    ImmutableList<String> expectedStderr =
        ImmutableList.of(
            "Message on stderr",
            "arg[--foo]",
            "arg[bar]",
            String.format("PWD: %s", filesystem.getRootPath().toString()),
            "CUSTOM_ENV:");

    RunAction action =
        new RunAction(
            runner.getRegistry(),
            "list",
            CommandLineArgsFactory.from(ImmutableList.of(script, "--foo", "bar")),
            ImmutableMap.of());

    StepExecutionResult result = runner.runAction(action).getResult();
    assertTrue(result.isSuccess());
    assertEquals(expectedStderr, getStderr(result));
  }

  @Test
  public void canRunBinariesAtWorkingDirRoot() throws CommandLineArgException, IOException {
    Path sourceScriptPath = Platform.isWindows() ? Paths.get("script.bat") : Paths.get("script.sh");
    Artifact sourceScript = SourceArtifactImpl.of(PathSourcePath.of(filesystem, sourceScriptPath));

    ImmutableList<String> expectedStderr =
        ImmutableList.of(
            "Message on stderr",
            "arg[--foo]",
            "arg[bar]",
            String.format("PWD: %s", filesystem.getRootPath().toString()),
            "CUSTOM_ENV:");

    RunAction action =
        new RunAction(
            runner.getRegistry(),
            "list",
            CommandLineArgsFactory.from(ImmutableList.of(sourceScript, "--foo", "bar")),
            ImmutableMap.of());

    StepExecutionResult result = runner.runAction(action).getResult();
    assertTrue(result.isSuccess());
    assertEquals(expectedStderr, getStderr(result));
  }

  @Test
  public void returnsErrorOnIOException() throws IOException, CommandLineArgException {
    filesystem.deleteFileAtPath(
        script.asBound().asBuildArtifact().getSourcePath().getResolvedPath());

    RunAction action =
        new RunAction(
            runner.getRegistry(),
            "list",
            CommandLineArgsFactory.from(ImmutableList.of(script, "--foo", "bar")),
            ImmutableMap.of());

    StepExecutionResult result = runner.runAction(action).getResult();
    assertFalse(result.isSuccess());
    assertFalse(result.getStderr().isPresent());
  }

  @Test
  public void returnsOutputOnFailure() throws CommandLineArgException, IOException {
    // TODO(pjameson): The actual error message with the exit code needs to be propagated somewhere
    //                 It's currently swallowed up by the legacy framework
    ImmutableList<String> expectedStderr =
        ImmutableList.of(
            "Message on stderr",
            "arg[--foo]",
            "arg[bar]",
            String.format("PWD: %s", filesystem.getRootPath().toString()),
            "CUSTOM_ENV:");

    RunAction action =
        new RunAction(
            runner.getRegistry(),
            "list",
            CommandLineArgsFactory.from(ImmutableList.of(script, "--foo", "bar")),
            ImmutableMap.of("EXIT_CODE", "1"));

    StepExecutionResult result = runner.runAction(action).getResult();

    assertFalse(result.isSuccess());
    assertEquals(expectedStderr, getStderr(result));
  }

  @Test
  public void getsInputsFromCommandLineArgs() throws IOException, EvalException {
    Artifact otherInput = runner.declareArtifact(Paths.get("other.txt"));
    Artifact output = runner.declareArtifact(Paths.get("out2.txt"));
    runner.runAction(
        new WriteAction(
            runner.getRegistry(),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of(otherInput.asOutputArtifact()),
            "contents",
            false));

    OutputArtifact outputArtifact = output.asOutputArtifact();
    RunAction action =
        new RunAction(
            runner.getRegistry(),
            "list",
            CommandLineArgsFactory.from(
                ImmutableList.of(
                    script,
                    "--foo",
                    "bar",
                    otherInput,
                    output.asSkylarkOutputArtifact(Location.BUILTIN))),
            ImmutableMap.of());

    assertEquals(ImmutableSortedSet.of(otherInput, script), action.getInputs());
    assertEquals(ImmutableSortedSet.of(outputArtifact), action.getOutputs());

    StepExecutionResult result = runner.runAction(action).getResult();
    assertTrue(result.isSuccess());
  }
}
