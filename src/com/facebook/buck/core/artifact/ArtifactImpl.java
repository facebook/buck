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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.starlark.rule.artifact.SkylarkOutputArtifactApi;
import com.facebook.buck.io.file.MorePaths;
import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.EvalException;
import java.nio.file.InvalidPathException;
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
  @AddToRuleKey private @Nullable ExplicitBuildTargetSourcePath sourcePath;

  private final BuildTarget target;
  private final Path genDir;
  private final Path basePath;
  private final Path outputPath;
  private final Location location;

  static ArtifactImpl of(
      BuildTarget target, Path genDir, Path packagePath, String outputPath, Location location) {
    try {
      return of(target, genDir, packagePath, Paths.get(outputPath), location);
    } catch (InvalidPathException e) {
      throw new ArtifactDeclarationException(
          ArtifactDeclarationException.Reason.INVALID_PATH, target, outputPath);
    }
  }

  static ArtifactImpl of(
      BuildTarget target, Path genDir, Path packagePath, Path outputPath, Location location)
      throws ArtifactDeclarationException {
    Path normalizedOutputPath = validateAndNormalizeOutputPath(target, outputPath);

    return new ArtifactImpl(target, genDir, packagePath, normalizedOutputPath, location);
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
  @Override
  public Path getOutputPath() {
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
  public int compareDeclared(DeclaredArtifact artifact) {
    Preconditions.checkState(!isBound() && artifact instanceof ArtifactImpl);
    ArtifactImpl other = (ArtifactImpl) artifact;
    return ComparisonChain.start()
        .compare(target, other.target)
        .compare(outputPath, other.outputPath)
        .compare(genDir, other.genDir)
        .compare(basePath, other.basePath)
        .result();
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

  private ArtifactImpl(
      BuildTarget target, Path genDir, Path packagePath, Path outputPath, Location location) {
    this.target = target;
    this.genDir = genDir;
    this.basePath = packagePath;
    this.outputPath = outputPath;
    this.location = location;
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
    this.location = Location.BUILTIN;
  }

  /** Get the location where this artifact was declared. {@link Location.BUILTIN} is valid */
  public Location getDeclaredLocation() {
    return location;
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
  public SkylarkOutputArtifactApi asSkylarkOutputArtifact(Location location) throws EvalException {
    if (isBound()) {
      throw new EvalException(
          location,
          String.format("%s is already bound. It cannot be used as an output artifact", this));
    }
    return ImmutableOutputArtifact.of(this);
  }

  @Override
  public OutputArtifact asOutputArtifact() {
    if (isBound()) {
      throw new HumanReadableException(
          "%s is already bound. It cannot be used as an output artifact", this);
    }
    return ImmutableOutputArtifact.of(this);
  }

  @Override
  public boolean isSource() {
    return false;
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    repr(printer, this, false);
  }

  static void repr(SkylarkPrinter printer, Artifact artifact, boolean isOutputArtifact) {
    printer.append("<generated ");
    if (isOutputArtifact) {
      printer.append("output");
    }
    printer.append("file '");
    printer.append(artifact.getShortPath());
    printer.append("'>");
  }

  @Override
  public String toString() {
    return toString(this, false);
  }

  static String toString(ArtifactImpl artifact, boolean isOutputArtifact) {
    StringBuilder builder = new StringBuilder("Artifact<");
    builder.append(artifact.getShortPath()).append(", ");
    if (isOutputArtifact) {
      builder.append("as output, ");
    }
    if (artifact.isBound()) {
      builder.append("bound to ").append(artifact.actionAnalysisDataKey);
    } else {
      builder.append("declared");
    }
    if (artifact.location != Location.BUILTIN) {
      if (artifact.isBound()) {
        builder.append(", declared");
      }
      builder.append(" at ").append(artifact.location.print());
    }
    return builder.append(">").toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ArtifactImpl)) {
      return false;
    }
    ArtifactImpl artifact = (ArtifactImpl) o;
    if (isBound()) {
      return Objects.equals(actionAnalysisDataKey, artifact.actionAnalysisDataKey)
          && Objects.equals(sourcePath, artifact.sourcePath);
    }
    return target.equals(artifact.target)
        && genDir.equals(artifact.genDir)
        && basePath.equals(artifact.basePath)
        && outputPath.equals(artifact.outputPath);
  }

  @Override
  public int hashCode() {
    return Objects.hash(actionAnalysisDataKey, sourcePath, target, genDir, basePath, outputPath);
  }
}
