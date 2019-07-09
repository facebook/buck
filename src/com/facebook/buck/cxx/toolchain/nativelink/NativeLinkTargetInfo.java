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
package com.facebook.buck.cxx.toolchain.nativelink;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

/** An implementation of {@link NativeLinkTarget} where the behavior is fixed when created. */
public final class NativeLinkTargetInfo implements NativeLinkTarget {
  private final BuildTarget target;
  private final NativeLinkTargetMode targetMode;
  private final ImmutableList<NativeLinkable> linkableDeps;
  private final NativeLinkableInput linkableInput;

  public NativeLinkTargetInfo(
      BuildTarget target,
      NativeLinkTargetMode targetMode,
      ImmutableList<NativeLinkable> linkableDeps,
      NativeLinkableInput linkableInput) {
    this.target = target;
    this.targetMode = targetMode;
    this.linkableDeps = linkableDeps;
    this.linkableInput = linkableInput;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return target;
  }

  @Override
  public NativeLinkTargetMode getNativeLinkTargetMode() {
    return targetMode;
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkTargetDeps(
      ActionGraphBuilder graphBuilder) {
    return linkableDeps;
  }

  @Override
  public NativeLinkableInput getNativeLinkTargetInput(
      ActionGraphBuilder graphBuilder, SourcePathResolver pathResolver) {
    return linkableInput;
  }

  @Override
  public Optional<Path> getNativeLinkTargetOutputPath() {
    return Optional.empty();
  }
}
