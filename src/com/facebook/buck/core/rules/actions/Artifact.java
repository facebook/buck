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
package com.facebook.buck.core.rules.actions;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import java.nio.file.Path;
import org.immutables.value.Value;
import org.immutables.value.Value.Style.ImplementationVisibility;

/**
 * An {@link Artifact} is a file used during the build stage. It can either be a source file for the
 * build or a generated file from a build step itself.
 */
public interface Artifact {

  /** An artifact generated from build */
  @Value.Immutable(builder = false, copy = false, prehash = true)
  @Value.Style(visibility = ImplementationVisibility.PACKAGE)
  abstract class BuildArtifact implements Artifact {

    /** @return the key to the {@link ActionAnalysisData} that owns this artifact */
    @Value.Parameter
    public abstract ActionAnalysisDataKey getActionDataKey();

    /** @return the {@link BuildTarget} of the rule that creates this {@link Artifact} */
    @Value.Parameter
    abstract BuildTarget getBuildTarget();

    /**
     * @return the buck-out package path folder that the specific output of this resides in. This is
     *     a buck-out/gen folder generated using the {@link BuildTarget}.
     */
    @Value.Parameter
    abstract Path getPackagePath();

    /** @return the output path relative to the {@link #getPackagePath()} */
    @Value.Parameter
    public abstract Path getOutputPath();

    /** @return the path to the artifact */
    @Value.Derived
    public ExplicitBuildTargetSourcePath getPath() {
      return ExplicitBuildTargetSourcePath.of(
          getBuildTarget(), getPackagePath().resolve(getOutputPath()));
    }
  }

  /** An artifact representing a source file */
  @Value.Immutable(builder = false, copy = false, prehash = true)
  interface SourceArtifact extends Artifact {

    /** @return the path to the source file */
    @Value.Parameter
    SourcePath getPath();
  }
}
