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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.SourceArtifactImpl;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.impl.TestActionExecutionRunner;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.devtools.build.lib.syntax.EvalException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CopyActionTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private ProjectFilesystem projectFilesystem;
  private TestActionExecutionRunner runner;

  @Before
  public void setUp() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    runner = new TestActionExecutionRunner(projectFilesystem, target);
  }

  @Test
  public void copiesContentsToFile() throws ActionCreationException, IOException, EvalException {

    PathSourcePath inputPath = PathSourcePath.of(projectFilesystem, Paths.get("input"));
    projectFilesystem.writeContentsToPath("contents", inputPath.getRelativePath());
    Artifact input = SourceArtifactImpl.of(inputPath);

    Artifact output = runner.declareArtifact(Paths.get("out"));

    TestActionExecutionRunner.ExecutionDetails<CopyAction> result =
        runner.runAction(
            new CopyAction(
                runner.getRegistry(), input, output.asOutputArtifact(), CopySourceMode.FILE));

    Path outputPath =
        Objects.requireNonNull(output.asBound().asBuildArtifact())
            .getSourcePath()
            .getResolvedPath();

    assertTrue(result.getResult().isSuccess());
    assertEquals(
        Optional.of("contents"),
        projectFilesystem.readFileIfItExists(projectFilesystem.resolve(outputPath)));

    assertTrue(outputPath.endsWith(Paths.get("out")));

    // now copy the result of the copy
    Artifact output2 = runner.declareArtifact(Paths.get("out2"));

    TestActionExecutionRunner.ExecutionDetails<CopyAction> result2 =
        runner.runAction(
            new CopyAction(
                runner.getRegistry(), output, output2.asOutputArtifact(), CopySourceMode.FILE));

    Path outputPath2 =
        Objects.requireNonNull(output2.asBound().asBuildArtifact())
            .getSourcePath()
            .getResolvedPath();

    assertTrue(result2.getResult().isSuccess());
    assertEquals(
        Optional.of("contents"),
        projectFilesystem.readFileIfItExists(projectFilesystem.resolve(outputPath2)));

    assertTrue(outputPath2.endsWith(Paths.get("out2")));
  }
}
