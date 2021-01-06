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
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.starlark.rule.artifact.SkylarkOutputArtifactApi;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;
import com.facebook.buck.io.file.MorePaths;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.EvalException;
import java.util.Optional;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** An artifact representing a source file */
@BuckStylePrehashedValue
public abstract class SourceArtifactImpl extends AbstractArtifact
    implements SourceArtifact, BoundArtifact {

  @Override
  public final boolean isBound() {
    return true;
  }

  /** @return the path to the source file */
  @Override
  @AddToRuleKey
  public abstract PathSourcePath getSourcePath();

  @Nullable
  @Override
  public SourceArtifact asSource() {
    return this;
  }

  @Nullable
  @Override
  public BuildArtifact asBuildArtifact() {
    return null;
  }

  @Value.Lazy
  @Override
  public String getBasename() {
    return getSourcePath().getRelativePath().getFileName().toString();
  }

  @Value.Lazy
  @Override
  public String getExtension() {
    return MorePaths.getFileExtension(getSourcePath().getRelativePath().getFileName());
  }

  @Value.Lazy
  @Override
  public Optional<Label> getOwnerTyped() {
    return Optional.empty();
  }

  @Value.Lazy
  @Override
  public boolean isSource() {
    return true;
  }

  @Value.Lazy
  @Override
  public String getShortPath() {
    return getSourcePath().getRelativePath().toString();
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<source file '");
    printer.append(getShortPath());
    printer.append("'>");
  }

  @Override
  public SkylarkOutputArtifactApi asSkylarkOutputArtifact(Location location) throws EvalException {
    throw new EvalException(
        location, String.format("source file %s cannot be used as an output artifact", this));
  }

  @Override
  public OutputArtifact asOutputArtifact() {
    throw new HumanReadableException("source file %s cannot be used as an output artifact", this);
  }

  public static SourceArtifactImpl of(PathSourcePath sourcePath) {
    return ImmutableSourceArtifactImpl.of(sourcePath);
  }
}
