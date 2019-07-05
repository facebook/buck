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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.io.file.MorePaths;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * An implementation of an {@link AbstractArtifact} that internally transitions from a {@link
 * DeclaredArtifact} to a {@link BoundArtifact} when bound with an action.
 *
 * <p>This is considered to be an instance of a {@link BuildArtifact} as these are only used to
 * represent {@link Artifact}s created by rules, not source files.
 */
class ArtifactImpl extends AbstractArtifact
    implements BoundArtifact, DeclaredArtifact, BuildArtifact {
  private static final Path PARENT_PATH = Paths.get("..");

  private @Nullable ActionAnalysisDataKey actionAnalysisDataKey = null;
  private @Nullable ExplicitBuildTargetSourcePath sourcePath;

  private final BuildTarget target;
  private final Path genDir;
  private final Path basePath;
  private final Path outputPath;

  static ArtifactImpl of(BuildTarget target, Path genDir, Path packagePath, Path outputPath)
      throws ArtifactDeclarationException {
    Path normalizedOutputPath = validateAndNormalizeOutputPath(target, outputPath);

    return new ArtifactImpl(target, genDir, packagePath, normalizedOutputPath);
  }

  private static Path validateAndNormalizeOutputPath(BuildTarget target, Path outputPath) {
    if (outputPath.isAbsolute()) {
      throw new ArtifactDeclarationException(
          ArtifactDeclarationException.Reason.ABSOLUTE_PATH, target, outputPath);
    }
    Path normalizedOutputPath = outputPath.normalize();
    if (MorePaths.isEmpty(normalizedOutputPath)) {
      throw new ArtifactDeclarationException(
          ArtifactDeclarationException.Reason.EMPTY_PATH, target, outputPath);
    }
    // Normalization should take care of any ".." in the middle of the path. If we still are
    // trying to access a parent dir, throw an error.
    if (normalizedOutputPath.startsWith(PARENT_PATH)) {
      throw new ArtifactDeclarationException(
          ArtifactDeclarationException.Reason.PATH_TRAVERSAL, target, outputPath);
    }
    return normalizedOutputPath;
  }

  static ArtifactImpl ofLegacy(
      BuildTarget target,
      Path genDir,
      Path packagePath,
      Path outputPath,
      ExplicitBuildTargetSourcePath sourcePath) {
    return new ArtifactImpl(target, genDir, packagePath, outputPath, sourcePath);
  }

  /** @return the {@link BuildTarget} of the rule that creates this {@link Artifact} */
  public BuildTarget getBuildTarget() {
    return target;
  }

  /**
   * @return the buck-out package path folder that the specific output of this resides in. This is a
   *     buck-out/gen folder generated using the {@link BuildTarget}.
   */
  Path getPackagePath() {
    return genDir.resolve(basePath);
  }

  /** @return the output path relative to the {@link #getPackagePath()} */
  Path getOutputPath() {
    return outputPath;
  }

  @Override
  public boolean isBound() {
    return sourcePath != null;
  }

  @Override
  public BuildArtifact materialize(ActionAnalysisDataKey key) {
    requireDeclared();
    actionAnalysisDataKey = key;
    sourcePath =
        ExplicitBuildTargetSourcePath.of(
            getBuildTarget(), getPackagePath().resolve(getOutputPath()));
    return this;
  }

  @Override
  public BuildArtifact asBuildArtifact() {
    requireBound();
    return this;
  }

  @Override
  @Nullable
  public ActionAnalysisDataKey getActionDataKey() {
    requireBound();
    return actionAnalysisDataKey;
  }

  @Override
  public ExplicitBuildTargetSourcePath getSourcePath() {
    requireBound();
    return Objects.requireNonNull(sourcePath);
  }

  private ArtifactImpl(BuildTarget target, Path genDir, Path packagePath, Path outputPath) {
    this.target = target;
    this.genDir = genDir;
    this.basePath = packagePath;
    this.outputPath = outputPath;
  }

  private ArtifactImpl(
      BuildTarget target,
      Path genDir,
      Path packagePath,
      Path outputPath,
      ExplicitBuildTargetSourcePath sourcePath) {
    this.target = target;
    this.genDir = genDir;
    this.basePath = packagePath;
    this.outputPath = outputPath;
    this.sourcePath = sourcePath;
  }

  @Override
  public @Nullable SourceArtifactImpl asSource() {
    return null;
  }

  @Override
  public String getBasename() {
    return outputPath.getFileName().toString();
  }

  @Override
  public String getExtension() {
    return MorePaths.getFileExtension(outputPath);
  }

  @Override
  public Optional<Label> getOwnerTyped() {
    try {
      return Optional.of(Label.parseAbsolute(target.getFullyQualifiedName(), ImmutableMap.of()));
    } catch (LabelSyntaxException e) {
      return Optional.empty();
    }
  }

  @Override
  public String getShortPath() {
    return basePath.resolve(outputPath).toString();
  }

  @Override
  public boolean isSource() {
    return false;
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<generated file '");
    printer.append(getShortPath());
    printer.append("'>");
  }
}
