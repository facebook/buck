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

package com.facebook.buck.rules.args;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.BuildArtifact;
import com.facebook.buck.core.artifact.BuildArtifactFactoryForTests;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.Location;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ArgFactoryTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void convertsStringToArg() {
    assertEquals(StringArg.of("foo"), ArgFactory.from("foo"));
  }

  @Test
  public void convertsIntegerToArg() {
    assertEquals(StringArg.of("1"), ArgFactory.from(1));
  }

  @Test
  public void convertsLabelToArg() {
    assertEquals(StringArg.of("foo"), ArgFactory.from("foo"));
  }

  @Test
  public void convertsArtifactToArg() throws LabelSyntaxException {
    assertEquals(
        StringArg.of("//foo:bar"),
        ArgFactory.from(Label.parseAbsolute("//foo:bar", ImmutableMap.of())));
  }

  @Test
  public void failsArgConversionOnNonBoundArtifact() {
    BuildArtifactFactoryForTests artifactFactory =
        new BuildArtifactFactoryForTests(
            BuildTargetFactory.newInstance("//foo:bar"), new FakeProjectFilesystem());
    Artifact artifact =
        artifactFactory.createDeclaredArtifact(Paths.get("foo", "bar.txt"), Location.BUILTIN);

    thrown.expect(IllegalArgumentException.class);
    ArgFactory.from(artifact);
  }

  @Test
  public void failsArgConversionOnOtherObjects() {
    BuildArtifactFactoryForTests artifactFactory =
        new BuildArtifactFactoryForTests(
            BuildTargetFactory.newInstance("//foo:bar"), new FakeProjectFilesystem());
    BuildArtifact artifact =
        artifactFactory.createBuildArtifact(Paths.get("foo", "bar.txt"), Location.BUILTIN);

    assertEquals(SourcePathArg.of(artifact.getSourcePath()), ArgFactory.from(artifact));
  }
}
