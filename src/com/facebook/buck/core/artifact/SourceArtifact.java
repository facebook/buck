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

import com.facebook.buck.core.sourcepath.PathSourcePath;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** An artifact representing a source file */
@Value.Immutable(builder = false, copy = false, prehash = true)
public abstract class SourceArtifact extends AbstractArtifact
    implements SourceArtifactApi, BoundArtifact {

  @Override
  public final boolean isBound() {
    return true;
  }

  @Value.Parameter
  @Override
  public abstract PathSourcePath getPath();

  @Nullable
  @Override
  public SourceArtifact asSource() {
    return this;
  }

  @Nullable
  @Override
  public BuildArtifactApi asBuildArtifact() {
    return null;
  }
}
