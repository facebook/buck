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

package com.facebook.buck.core.rules.actions.lib.args;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.artifact.BuildArtifactFactoryForTests;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.artifact.SourceArtifactImpl;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.EvalException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class CommandLineArgsFactoryTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  private final ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final Path source = Paths.get("src", "main.cpp");
  private ArtifactFilesystem artifactFilesystem;

  @Before
  public void setUp() {
    artifactFilesystem = new ArtifactFilesystem(filesystem);
  }

  ImmutableList<String> stringify(CommandLineArgs args) {
    return new ExecCompatibleCommandLineBuilder(artifactFilesystem)
        .build(args)
        .getCommandLineArgs();
  }

  @Test
  public void handlesEmptyList() {
    CommandLineArgs args = CommandLineArgsFactory.from(ImmutableList.of());

    assertTrue(args instanceof ListCommandLineArgs);
    assertEquals(ImmutableList.of(), stringify(args));
  }

  @Test
  public void createsListArgsIfNoCommandLineArgs() throws LabelSyntaxException {
    CommandLineArgs args =
        CommandLineArgsFactory.from(
            ImmutableList.of(
                1,
                "foo",
                Label.parseAbsolute("//foo:bar", ImmutableMap.of()),
                SourceArtifactImpl.of(PathSourcePath.of(filesystem, source))));

    assertTrue(args instanceof ListCommandLineArgs);
    assertEquals(ImmutableList.of("1", "foo", "//foo:bar", source.toString()), stringify(args));
  }

  @Test
  public void createsAggregateArgsIfGivenOnlyCommandLineArgs() {
    CommandLineArgs args =
        CommandLineArgsFactory.from(
            ImmutableList.of(
                CommandLineArgsFactory.from(ImmutableList.of(1)),
                CommandLineArgsFactory.from(ImmutableList.of("2")),
                CommandLineArgsFactory.from(ImmutableList.of(3)),
                CommandLineArgsFactory.from(ImmutableList.of("4"))));

    assertTrue(args instanceof AggregateCommandLineArgs);
    assertEquals(ImmutableList.of("1", "2", "3", "4"), stringify(args));
  }

  @Test
  public void createsAggregateArgsIfGivenMix() throws LabelSyntaxException {
    CommandLineArgs args =
        CommandLineArgsFactory.from(
            ImmutableList.of(
                1,
                "foo",
                Label.parseAbsolute("//foo:bar", ImmutableMap.of()),
                SourceArtifactImpl.of(PathSourcePath.of(filesystem, source)),
                CommandLineArgsFactory.from(ImmutableList.of(2, "bar"))));

    assertTrue(args instanceof AggregateCommandLineArgs);
    assertEquals(
        ImmutableList.of("1", "foo", "//foo:bar", source.toString(), "2", "bar"), stringify(args));
  }

  @Test
  public void rejectsInvalidCommandLineArgsForArgList() {
    thrown.expect(CommandLineArgException.class);
    CommandLineArgsFactory.from(ImmutableList.of(ImmutableList.of()));
  }

  @Test
  public void unboundArtifactsNonOutputArtifactsError() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    BuildArtifactFactoryForTests artifactFactory =
        new BuildArtifactFactoryForTests(target, new FakeProjectFilesystem());
    Artifact artifact =
        artifactFactory.createDeclaredArtifact(Paths.get("out.txt"), Location.BUILTIN);

    thrown.expect(CommandLineArgException.class);
    thrown.expectMessage("was not used as the output to an action");
    CommandLineArgsFactory.from(ImmutableList.of(1, artifact));
  }

  @Test
  public void allowsUnboundOutputArtifacts() throws EvalException {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    BuildArtifactFactoryForTests artifactFactory =
        new BuildArtifactFactoryForTests(target, new FakeProjectFilesystem());
    Artifact artifact =
        artifactFactory.createDeclaredArtifact(Paths.get("out.txt"), Location.BUILTIN);

    CommandLineArgs args =
        CommandLineArgsFactory.from(
            ImmutableList.of(artifact.asSkylarkOutputArtifact(Location.BUILTIN)));

    ImmutableList.Builder<Artifact> inputs = ImmutableList.builder();
    ImmutableList.Builder<OutputArtifact> outputs = ImmutableList.builder();
    args.visitInputsAndOutputs(inputs::add, outputs::add);

    assertEquals(ImmutableList.of(), inputs.build());
    assertEquals(ImmutableList.of(artifact.asOutputArtifact()), outputs.build());
  }
}
