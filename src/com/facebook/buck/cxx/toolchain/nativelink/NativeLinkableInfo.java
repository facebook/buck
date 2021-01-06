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

package com.facebook.buck.cxx.toolchain.nativelink;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.util.Memoizer;
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
// TODO: This class should really be final, but extraneous instances of
// `SwiftRuntimeNativeLinkableGroup` break the default hashcode/equals used in sets/maps, requiring
// those instances to provide their own overloads.
public class NativeLinkableInfo implements NativeLinkable {
  private final Cache<LinkableInputCacheKey, NativeLinkableInput> nativeLinkableCache =
      CacheBuilder.newBuilder().build();
  // TODO(cjhopman): We should remove this delegate, everything should be fixed when this is
  // created.
  /**
   * The Delegate allows instances to create {@link NativeLinkableInput} when requested. The
   * returned values will be cached.
   */
  public interface Delegate {
    NativeLinkableInput computeInput(
        ActionGraphBuilder graphBuilder,
        Linker.LinkableDepType type,
        boolean forceLinkWhole,
        TargetConfiguration targetConfiguration);

    ImmutableMap<String, SourcePath> getSharedLibraries(ActionGraphBuilder graphBuilder);

    @SuppressWarnings("unused")
    default boolean isPrebuiltSOForHaskellOmnibus(ActionGraphBuilder graphBuilder) {
      return false;
    }
  }

  /** Creates a delegate that always returns a fixed instance. */
  public static Delegate fixedDelegate(
      NativeLinkableInput instance, final ImmutableMap<String, SourcePath> sharedLibraries) {
    return new Delegate() {
      @Override
      public NativeLinkableInput computeInput(
          ActionGraphBuilder graphBuilder,
          Linker.LinkableDepType type,
          boolean forceLinkWhole,
          TargetConfiguration targetConfiguration) {
        return instance;
      }

      @Override
      public ImmutableMap<String, SourcePath> getSharedLibraries(ActionGraphBuilder graphBuilder) {
        return sharedLibraries;
      }
    };
  }

  /**
   * Configuration is used for configuring the less-commonly changed parts of the @{link
   * NativeLinkableInfo}. Most cases can just use the default values.
   */
  public static class Configuration {

    private Configuration() {}

    private ImmutableList<? extends Arg> exportedLinkerFlags = ImmutableList.of();
    private ImmutableList<? extends Arg> exportedPostLinkerFlags = ImmutableList.of();
    private boolean supportsOmnibusLinking = true;
    private boolean supportsOmnibusLinkingForHaskell = false;
    private Optional<Boolean> forceLinkWholeForHaskellOmnibus = Optional.empty();
    private boolean shouldBeLinkedInAppleTestAndHost = false;
    private Optional<? extends NativeLinkTarget> nativeLinkTarget = Optional.empty();

    public Configuration setExportedLinkerFlags(ImmutableList<? extends Arg> exportedLinkerFlags) {
      this.exportedLinkerFlags = exportedLinkerFlags;
      return this;
    }

    public Configuration setExportedPostLinkerFlags(
        ImmutableList<? extends Arg> exportedPostLinkerFlags) {
      this.exportedPostLinkerFlags = exportedPostLinkerFlags;
      return this;
    }

    public Configuration setSupportsOmnibusLinking(boolean supportsOmnibusLinking) {
      this.supportsOmnibusLinking = supportsOmnibusLinking;
      return this;
    }

    public Configuration setHaskellOmnibusLinkingOptions(
        boolean supportsOmnibusLinkingForHaskell, boolean forceLinkWholeForHaskellOmnibus) {
      this.supportsOmnibusLinkingForHaskell = supportsOmnibusLinkingForHaskell;
      this.forceLinkWholeForHaskellOmnibus = Optional.of(forceLinkWholeForHaskellOmnibus);
      return this;
    }

    public Configuration setNativeLinkTarget(
        Optional<? extends NativeLinkTarget> nativeLinkTarget) {
      this.nativeLinkTarget = nativeLinkTarget;
      return this;
    }

    public Configuration setShouldBeLinkedInAppleTestAndHost(
        boolean shouldBeLinkedInAppleTestAndHost) {
      this.shouldBeLinkedInAppleTestAndHost = shouldBeLinkedInAppleTestAndHost;
      return this;
    }
  }

  /**
   * Returns a Configuration with all the default values. These can be overriden with the various
   * set methods.
   */
  public static Configuration defaults() {
    return new Configuration();
  }

  private final BuildTarget buildTarget;
  private final ImmutableList<NativeLinkable> deps;
  private final ImmutableList<NativeLinkable> exportedDeps;
  private final NativeLinkableGroup.Linkage preferredLinkage;
  private final ImmutableList<? extends Arg> exportedLinkerFlags;
  private final ImmutableList<? extends Arg> exportedPostLinkerFlags;
  private final boolean supportsOmnibusLinking;
  private final boolean supportsOmnibusLinkingForHaskell;
  private final Optional<Boolean> forceLinkWholeForHaskellOmnibus;
  private final boolean shouldBeLinkedInAppleTestAndHost;
  private final Optional<? extends NativeLinkTarget> nativeLinkTarget;
  private final Delegate delegate;
  private final String ruleType;

  private final Memoizer<ImmutableMap<String, SourcePath>> sharedLibrariesMemoizer =
      new Memoizer<>();

  public NativeLinkableInfo(
      BuildTarget buildTarget,
      String ruleType,
      ImmutableList<NativeLinkable> deps,
      ImmutableList<NativeLinkable> exportedDeps,
      NativeLinkableGroup.Linkage preferredLinkage,
      Delegate delegate,
      Configuration config) {
    this.buildTarget = buildTarget;
    this.ruleType = ruleType;
    this.deps = deps;
    this.exportedDeps = exportedDeps;
    this.exportedLinkerFlags = config.exportedLinkerFlags;
    this.exportedPostLinkerFlags = config.exportedPostLinkerFlags;
    this.preferredLinkage = preferredLinkage;
    this.supportsOmnibusLinking = config.supportsOmnibusLinking;
    this.supportsOmnibusLinkingForHaskell = config.supportsOmnibusLinkingForHaskell;
    this.forceLinkWholeForHaskellOmnibus = config.forceLinkWholeForHaskellOmnibus;
    this.shouldBeLinkedInAppleTestAndHost = config.shouldBeLinkedInAppleTestAndHost;
    this.nativeLinkTarget = config.nativeLinkTarget;
    this.delegate = delegate;
  }

  @Override
  public String toString() {
    return String.format("NativeLinkableInfo: %s (%s)", ruleType, buildTarget.toString());
  }

  @Override
  public String getRuleType() {
    return ruleType;
  }

  /** This is just used internally as a key for caching. */
  @BuckStyleValue
  interface LinkableInputCacheKey {
    boolean getForceLinkWhole();

    Linker.LinkableDepType getLinkableDepType();

    TargetConfiguration getTargetConfiguration();
  }

  @Override
  public boolean supportsOmnibusLinking() {
    return supportsOmnibusLinking;
  }

  @Override
  public boolean isPrebuiltSOForHaskellOmnibus(ActionGraphBuilder graphBuilder) {
    return delegate.isPrebuiltSOForHaskellOmnibus(graphBuilder);
  }

  @Override
  public boolean supportsOmnibusLinkingForHaskell() {
    return supportsOmnibusLinkingForHaskell;
  }

  @Override
  public boolean forceLinkWholeForHaskellOmnibus() {
    if (!forceLinkWholeForHaskellOmnibus.isPresent()) {
      throw new IllegalStateException(
          String.format("Unexpected rule type in omnibus link %s.", getBuildTarget()));
    }
    return forceLinkWholeForHaskellOmnibus.get();
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
          ImmutableLinkableInputCacheKey.of(forceLinkWhole, type, targetConfiguration),
          () -> delegate.computeInput(graphBuilder, type, forceLinkWhole, targetConfiguration));
    } catch (ExecutionException e) {
      Throwables.throwIfUnchecked(e);
      throw new UncheckedExecutionException(e.getCause());
    }
  }

  @Override
  public Optional<NativeLinkTarget> getNativeLinkTarget(
      ActionGraphBuilder graphBuilder, boolean includePrivateLinkerFlags) {
    return nativeLinkTarget.map(NativeLinkTarget.class::cast);
  }

  @Override
  public NativeLinkableGroup.Linkage getPreferredLinkage() {
    return preferredLinkage;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(ActionGraphBuilder graphBuilder) {
    return sharedLibrariesMemoizer.get(() -> delegate.getSharedLibraries(graphBuilder));
  }

  @Override
  public Iterable<? extends Arg> getExportedLinkerFlags(ActionGraphBuilder graphBuilder) {
    return exportedLinkerFlags;
  }

  @Override
  public Iterable<? extends Arg> getExportedPostLinkerFlags(ActionGraphBuilder graphBuilder) {
    return exportedPostLinkerFlags;
  }

  @Override
  public boolean shouldBeLinkedInAppleTestAndHost() {
    return shouldBeLinkedInAppleTestAndHost;
  }
}
