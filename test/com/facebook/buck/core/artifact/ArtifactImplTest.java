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

package com.facebook.buck.core.artifact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.vfs.PathFragment;
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
    DeclaredArtifact artifact =
        ArtifactImpl.of(target, genDir, packagePath, path, Location.BUILTIN);
    assertFalse(artifact.isBound());

    ActionAnalysisDataKey key = ActionAnalysisDataKey.of(target, new ActionAnalysisData.ID("a"));
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
    ArtifactImpl.of(target, genDir, packagePath, Paths.get(""), Location.BUILTIN);
  }

  @Test
  public void rejectsEmptyPathAfterPathTraversal() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");

    expectedException.expect(ArtifactDeclarationException.class);
    ArtifactImpl.of(target, genDir, packagePath, Paths.get("foo/.."), Location.BUILTIN);
  }

  @Test
  public void rejectsDotPath() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");

    expectedException.expect(ArtifactDeclarationException.class);
    ArtifactImpl.of(target, genDir, packagePath, Paths.get("."), Location.BUILTIN);
  }

  @Test
  public void rejectsPrefixedDotPath() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");

    expectedException.expect(ArtifactDeclarationException.class);
    ArtifactImpl.of(target, genDir, packagePath, Paths.get("./foo/.."), Location.BUILTIN);
  }

  @Test
  public void rejectsSuffixedDotPath() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");

    expectedException.expect(ArtifactDeclarationException.class);
    ArtifactImpl.of(target, genDir, packagePath, Paths.get("foo/../."), Location.BUILTIN);
  }

  @Test
  public void rejectsPrefixedUpwardPathTraversal() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path genDir = Paths.get("buck-out/gen");
    Path packagePath = Paths.get("my/foo__");

    expectedException.expect(ArtifactDeclarationException.class);
    ArtifactImpl.of(target, genDir, packagePath, Paths.get("../bar"), Location.BUILTIN);
  }

  @Test
  public void rejectsSuffixedUpwardPathTraversal() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path genDir = Paths.get("buck-out/gen");
    Path packagePath = Paths.get("my/foo__");

    expectedException.expect(ArtifactDeclarationException.class);
    ArtifactImpl.of(target, genDir, packagePath, Paths.get("foo/../.."), Location.BUILTIN);
  }

  @Test
  public void rejectsUpwardPathTraversal() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");

    expectedException.expect(ArtifactDeclarationException.class);
    ArtifactImpl.of(target, genDir, packagePath, Paths.get("foo/../../bar"), Location.BUILTIN);
  }

  @Test
  public void rejectsAbsolutePath() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");

    expectedException.expect(ArtifactDeclarationException.class);
    ArtifactImpl.of(target, genDir, packagePath, Paths.get("").toAbsolutePath(), Location.BUILTIN);
  }

  @Test
  public void rejectsInvalidPath() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");

    expectedException.expect(ArtifactDeclarationException.class);
    expectedException.expectMessage("Path 'foo\\u0000bar.txt' in target '//my:foo' is not valid");
    ArtifactImpl.of(target, genDir, packagePath, "foo\0bar.txt", Location.BUILTIN);
  }

  @Test
  public void normalizesPaths() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path genDir = Paths.get("buck-out/gen");
    Path packagePath = Paths.get("my/foo__");

    DeclaredArtifact artifact =
        ArtifactImpl.of(
            target, genDir, packagePath, Paths.get("bar/baz/.././blargl.sh"), Location.BUILTIN);
    assertEquals(Paths.get("my", "foo__", "bar", "blargl.sh").toString(), artifact.getShortPath());
  }

  @Test
  public void skylarkFunctionsWork() throws LabelSyntaxException {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");

    ArtifactImpl artifact =
        ArtifactImpl.of(target, genDir, packagePath, Paths.get("bar/baz.cpp"), Location.BUILTIN);

    String expectedShortPath = Paths.get("my", "foo__", "bar", "baz.cpp").toString();

    assertEquals("baz.cpp", artifact.getBasename());
    assertEquals("cpp", artifact.getExtension());
    assertEquals(Label.parseAbsolute("//my:foo", ImmutableMap.of()), artifact.getOwner());
    assertEquals(expectedShortPath, artifact.getShortPath());
    assertFalse(artifact.isSource());
    assertEquals(String.format("<generated file '%s'>", expectedShortPath), Printer.repr(artifact));
  }

  @Test
  public void equalsArtifactHasEqualsTrueAndSameHashCode() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");
    ArtifactImpl artifact1 =
        ArtifactImpl.of(target, genDir, packagePath, Paths.get("some/path"), Location.BUILTIN);
    ArtifactImpl artifact2 =
        ArtifactImpl.of(target, genDir, packagePath, Paths.get("some/path"), Location.BUILTIN);

    assertEquals(artifact1, artifact2);
    assertEquals(artifact1.hashCode(), artifact2.hashCode());

    ActionAnalysisDataKey key = ActionAnalysisDataKey.of(target, new ActionAnalysisData.ID("a"));

    artifact1 = (ArtifactImpl) artifact1.materialize(key);
    artifact2 = (ArtifactImpl) artifact2.materialize(key);

    assertEquals(artifact1, artifact2);
    assertEquals(artifact1.hashCode(), artifact2.hashCode());
  }

  @Test
  public void differentPathsAreNotEqual() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");
    ArtifactImpl artifact1 =
        ArtifactImpl.of(target, genDir, packagePath, Paths.get("some/path1"), Location.BUILTIN);
    ArtifactImpl artifact2 =
        ArtifactImpl.of(target, genDir, packagePath, Paths.get("some/path2"), Location.BUILTIN);

    assertNotEquals(artifact1, artifact2);
  }

  @Test
  public void differentActionKeysAreNotEqual() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");
    ArtifactImpl artifact1 =
        ArtifactImpl.of(target, genDir, packagePath, Paths.get("some/path"), Location.BUILTIN);
    ArtifactImpl artifact2 =
        ArtifactImpl.of(target, genDir, packagePath, Paths.get("some/path"), Location.BUILTIN);

    ActionAnalysisDataKey key1 = ActionAnalysisDataKey.of(target, new ActionAnalysisData.ID("a"));
    ActionAnalysisDataKey key2 = ActionAnalysisDataKey.of(target, new ActionAnalysisData.ID("a"));

    artifact1 = (ArtifactImpl) artifact1.materialize(key1);
    artifact2 = (ArtifactImpl) artifact2.materialize(key2);

    assertNotEquals(artifact1, artifact2);
  }

  @Test
  public void toStringMakesSense() throws EvalException {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my", "foo__");
    Location location =
        Location.fromPathAndStartColumn(
            PathFragment.create("foo").getChild("bar.bzl"), 0, 5, new Location.LineAndColumn(3, 4));
    ActionAnalysisDataKey key1 = ActionAnalysisDataKey.of(target, new ActionAnalysisData.ID("a"));
    ActionAnalysisDataKey key2 = ActionAnalysisDataKey.of(target, new ActionAnalysisData.ID("a"));

    ArtifactImpl declaredWithLocation =
        ArtifactImpl.of(target, genDir, packagePath, Paths.get("some/path"), location);
    ArtifactImpl boundWithLocation =
        ArtifactImpl.of(target, genDir, packagePath, Paths.get("some/path"), location);
    boundWithLocation.materialize(key1);
    ArtifactImpl declaredWithoutLocation =
        ArtifactImpl.of(target, genDir, packagePath, Paths.get("some/path"), Location.BUILTIN);
    ArtifactImpl boundWithoutLocation =
        ArtifactImpl.of(target, genDir, packagePath, Paths.get("some/path"), Location.BUILTIN);
    boundWithoutLocation.materialize(key2);

    String artifactPathString = Paths.get("my", "foo__", "some", "path").toString();
    // Location always prints with forward slashes, Path uses platform native
    String extensionPathString = "foo/bar.bzl";

    String expectedWithLocation =
        String.format("Artifact<%s, declared at %s:3:4>", artifactPathString, extensionPathString);

    String expectedBoundWithLocation =
        String.format(
            "Artifact<%s, bound to %s, declared at %s:3:4>",
            artifactPathString, key1, extensionPathString);

    String expectedWithoutLocation = String.format("Artifact<%s, declared>", artifactPathString);

    String expectedBoundWithoutLocation =
        String.format("Artifact<%s, bound to %s>", artifactPathString, key2);

    String expectedWithLocationAsOutput =
        String.format(
            "Artifact<%s, as output, declared at %s:3:4>", artifactPathString, extensionPathString);

    String expectedWithoutLocationAsOutput =
        String.format("Artifact<%s, as output, declared>", artifactPathString);

    assertEquals(expectedWithLocation, declaredWithLocation.toString());
    assertEquals(
        expectedWithLocationAsOutput,
        declaredWithLocation.asSkylarkOutputArtifact(Location.BUILTIN).toString());
    assertEquals(expectedWithLocationAsOutput, declaredWithLocation.asOutputArtifact().toString());
    assertEquals(expectedBoundWithLocation, boundWithLocation.toString());
    assertEquals(expectedWithoutLocation, declaredWithoutLocation.toString());
    assertEquals(
        expectedWithoutLocationAsOutput,
        declaredWithoutLocation.asSkylarkOutputArtifact(Location.BUILTIN).toString());
    assertEquals(
        expectedWithoutLocationAsOutput, declaredWithoutLocation.asOutputArtifact().toString());
    assertEquals(expectedBoundWithoutLocation, boundWithoutLocation.toString());
  }

  @Test
  public void refusesToCreateSkylarkOutputArtifactIfBound() throws EvalException {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");
    Path path = Paths.get("bar");
    ArtifactImpl artifact = ArtifactImpl.of(target, genDir, packagePath, path, Location.BUILTIN);
    ActionAnalysisDataKey key = ActionAnalysisDataKey.of(target, new ActionAnalysisData.ID("a"));
    BuildArtifact materialized = artifact.materialize(key);

    assertTrue(materialized.isBound());

    expectedException.expect(EvalException.class);
    expectedException.expectMessage("cannot be used as an output artifact");
    artifact.asSkylarkOutputArtifact(Location.BUILTIN);
  }

  @Test
  public void refusesToCreateOutputArtifactIfBound() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");
    Path path = Paths.get("bar");
    ArtifactImpl artifact = ArtifactImpl.of(target, genDir, packagePath, path, Location.BUILTIN);
    ActionAnalysisDataKey key = ActionAnalysisDataKey.of(target, new ActionAnalysisData.ID("a"));
    BuildArtifact materialized = artifact.materialize(key);

    assertTrue(materialized.isBound());

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("cannot be used as an output artifact");
    artifact.asOutputArtifact();
  }

  @Test
  public void isImmutable() throws EvalException {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    Path packagePath = Paths.get("my/foo__");
    Path path = Paths.get("bar");
    ArtifactImpl artifact = ArtifactImpl.of(target, genDir, packagePath, path, Location.BUILTIN);

    assertFalse(artifact.isBound());
    assertTrue(artifact.isImmutable());

    assertTrue(artifact.asSkylarkOutputArtifact(Location.BUILTIN).isImmutable());

    ActionAnalysisDataKey key = ActionAnalysisDataKey.of(target, new ActionAnalysisData.ID("a"));
    BuildArtifact materialized = artifact.materialize(key);

    assertTrue(materialized.isBound());
    assertTrue(artifact.isImmutable());
  }
}
