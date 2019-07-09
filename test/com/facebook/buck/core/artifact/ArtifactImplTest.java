/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.artifact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ImmutableActionAnalysisDataKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.syntax.Printer;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ArtifactImplTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  private final Path genDir = Paths.get("buck-out/gen");

  @Test
  public void artifactTransitionsToBuildArtifact() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");
    Path path = Paths.get("bar");
    DeclaredArtifact artifact = ArtifactImpl.of(target, genDir, packagePath, path);
    assertFalse(artifact.isBound());

    ImmutableActionAnalysisDataKey key =
        ImmutableActionAnalysisDataKey.of(target, new ActionAnalysisData.ID() {});
    BuildArtifact materialized = artifact.materialize(key);

    assertTrue(materialized.isBound());
    assertEquals(key, materialized.getActionDataKey());
    assertEquals(
        ExplicitBuildTargetSourcePath.of(target, genDir.resolve(packagePath).resolve(path)),
        materialized.getSourcePath());
  }

  @Test
  public void rejectsEmptyPath() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");

    expectedException.expect(ArtifactDeclarationException.class);
    ArtifactImpl.of(target, genDir, packagePath, Paths.get(""));
  }

  @Test
  public void rejectsEmptyPathAfterPathTraversal() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");

    expectedException.expect(ArtifactDeclarationException.class);
    ArtifactImpl.of(target, genDir, packagePath, Paths.get("foo/.."));
  }

  @Test
  public void rejectsPrefixedUpwardPathTraversal() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path genDir = Paths.get("buck-out/gen");
    Path packagePath = Paths.get("my/foo__");

    expectedException.expect(ArtifactDeclarationException.class);
    ArtifactImpl.of(target, genDir, packagePath, Paths.get("../bar"));
  }

  @Test
  public void rejectsSuffixedUpwardPathTraversal() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path genDir = Paths.get("buck-out/gen");
    Path packagePath = Paths.get("my/foo__");

    expectedException.expect(ArtifactDeclarationException.class);
    ArtifactImpl.of(target, genDir, packagePath, Paths.get("foo/../.."));
  }

  @Test
  public void rejectsUpwardPathTraversal() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");

    expectedException.expect(ArtifactDeclarationException.class);
    ArtifactImpl.of(target, genDir, packagePath, Paths.get("foo/../../bar"));
  }

  @Test
  public void rejectsAbsolutePath() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");

    expectedException.expect(ArtifactDeclarationException.class);
    ArtifactImpl.of(target, genDir, packagePath, Paths.get("").toAbsolutePath());
  }

  @Test
  public void normalizesPaths() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path genDir = Paths.get("buck-out/gen");
    Path packagePath = Paths.get("my/foo__");

    DeclaredArtifact artifact =
        ArtifactImpl.of(target, genDir, packagePath, Paths.get("bar/baz/.././blargl.sh"));
    assertEquals(Paths.get("my", "foo__", "bar", "blargl.sh").toString(), artifact.getShortPath());
  }

  @Test
  public void skylarkFunctionsWork() throws LabelSyntaxException {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");

    ArtifactImpl artifact =
        (ArtifactImpl) ArtifactImpl.of(target, genDir, packagePath, Paths.get("bar/baz.cpp"));

    String expectedShortPath = Paths.get("my", "foo__", "bar", "baz.cpp").toString();

    assertEquals("baz.cpp", artifact.getBasename());
    assertEquals("cpp", artifact.getExtension());
    assertEquals(Label.parseAbsolute("//my:foo", ImmutableMap.of()), artifact.getOwner());
    assertEquals(expectedShortPath, artifact.getShortPath());
    assertFalse(artifact.isSource());
    assertEquals(String.format("<generated file '%s'>", expectedShortPath), Printer.repr(artifact));
  }
}
