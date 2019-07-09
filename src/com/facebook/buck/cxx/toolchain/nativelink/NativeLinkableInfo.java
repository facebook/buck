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
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * An implementation of {@link NativeLinkable} where (most of) the behavior is fixed when created.
 */
public final class NativeLinkableInfo implements NativeLinkable {
  private final Cache<LinkableInputCacheKey, NativeLinkableInput> nativeLinkableCache =
      CacheBuilder.newBuilder().build();

  // TODO(cjhopman): We should remove this delegate, everything should be fixed when this is
  // created.
  /**
   * The Delegate allows instances to create {@link NativeLinkableInput} when requested. The
   * returned values will be cached.
   */
  interface Delegate {
    NativeLinkableInput computeInput(
        ActionGraphBuilder graphBuilder,
        Linker.LinkableDepType type,
        boolean forceLinkWhole,
        TargetConfiguration targetConfiguration);
  }

  private final BuildTarget buildTarget;
  private final ImmutableList<NativeLinkable> deps;
  private final ImmutableList<NativeLinkable> exportedDeps;
  private final NativeLinkableGroup.Linkage preferredLinkage;
  private final ImmutableMap<String, SourcePath> sharedLibraries;
  private final Delegate delegate;

  public NativeLinkableInfo(
      BuildTarget buildTarget,
      ImmutableList<NativeLinkable> deps,
      ImmutableList<NativeLinkable> exportedDeps,
      NativeLinkableGroup.Linkage preferredLinkage,
      ImmutableMap<String, SourcePath> sharedLibraries,
      Delegate delegate) {
    this.buildTarget = buildTarget;
    this.deps = deps;
    this.exportedDeps = exportedDeps;
    this.preferredLinkage = preferredLinkage;
    this.sharedLibraries = sharedLibraries;
    this.delegate = delegate;
  }

  /** This is just used internally as a key for caching. */
  @BuckStyleValue
  interface LinkableInputCacheKey {
    boolean getForceLinkWhole();

    Linker.LinkableDepType getLinkableDepType();

    TargetConfiguration getTargetConfiguration();
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableDeps(ActionGraphBuilder graphBuilder) {
    return deps;
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(
      ActionGraphBuilder graphBuilder) {
    return exportedDeps;
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ActionGraphBuilder graphBuilder,
      TargetConfiguration targetConfiguration) {
    try {
      return nativeLinkableCache.get(
          new ImmutableLinkableInputCacheKey(forceLinkWhole, type, targetConfiguration),
          () -> delegate.computeInput(graphBuilder, type, forceLinkWhole, targetConfiguration));
    } catch (ExecutionException e) {
      Throwables.throwIfUnchecked(e);
      throw new UncheckedExecutionException(e.getCause());
    }
  }

  @Override
  public Optional<NativeLinkTarget> getNativeLinkTarget(ActionGraphBuilder graphBuilder) {
    return Optional.empty();
  }

  @Override
  public NativeLinkableGroup.Linkage getPreferredLinkage() {
    return preferredLinkage;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(ActionGraphBuilder graphBuilder) {
    return sharedLibraries;
  }

  @Override
  public boolean shouldBeLinkedInAppleTestAndHost() {
    return false;
  }
}
