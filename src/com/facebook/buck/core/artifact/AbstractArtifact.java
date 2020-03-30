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

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.syntax.Runtime;
import java.util.Optional;

/**
 * The abstract {@link Artifact} with information on whether or not the artifact is a bound
 * artifact. A bound artifact is either a file in the repo, or or a file that is bound to an action.
 * An unbound artifact is a declared artifact that will become a build artifact once bound with an
 * action.
 */
abstract class AbstractArtifact implements Artifact {

  /** @return whether the artifact is bound, as described above */
  @Override
  public abstract boolean isBound();

  /** @return a view of this artifact as a {@link BoundArtifact} */
  @Override
  public final BoundArtifact asBound() {
    requireBound();

    return (BoundArtifact) this;
  }

  /** @return a view of this artifact as a {@link DeclaredArtifact} */
  @Override
  public final DeclaredArtifact asDeclared() {
    requireDeclared();
    return (DeclaredArtifact) this;
  }

  protected void requireBound() {
    Preconditions.checkState(
        isBound(), "Requesting the BoundArtifact but this artifact is actually unbound.");
  }

  protected void requireDeclared() {
    Preconditions.checkState(
        !isBound(), "Requesting the Declared but this artifact is actually already bound.");
  }

  @Override
  public final Object getOwner() {
    return getOwnerTyped().map(Object.class::cast).orElse(Runtime.NONE);
  }

  /**
   * @return The Label of the rule that created this artifact, or {@link Optional#empty()} if not
   *     applicable. This is converted to a Skylark appropriate type by {@link #getOwner()}
   */
  protected abstract Optional<Label> getOwnerTyped();

  @Override
  public final int compareTo(Artifact artifact) {
    if (artifact == this) {
      return 0;
    }

    int classComparison = compareClasses(artifact);
    if (classComparison != 0) {
      return classComparison;
    }

    int boundComparison = Boolean.compare(isBound(), artifact.isBound());
    if (boundComparison != 0) {
      return boundComparison;
    }

    if (isBound()) {
      return asBound().getSourcePath().compareTo(artifact.asBound().getSourcePath());
    }
    return asDeclared().compareDeclared(artifact.asDeclared());
  }

  private final int compareClasses(Artifact other) {
    if (this.getClass() != other.getClass()) {
      int result = this.getClass().getName().compareTo(other.getClass().getName());
      if (result != 0) {
        return result;
      }

      Preconditions.checkState(
          this.getClass().equals(other.getClass()),
          "Classes are different but have the same name: %s %s",
          this.getClass(),
          other.getClass());
    }

    return 0;
  }
}
