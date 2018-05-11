/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import com.facebook.buck.core.model.actiongraph.ActionGraph;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.WatchmanOverflowEvent;
import com.facebook.buck.io.WatchmanPathEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.util.cache.InstrumentingCacheStatsTracker;
import com.facebook.buck.util.cache.NoOpCacheStatsTracker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import javax.annotation.Nullable;

/** Class which encapsulates all effort to cache and reuse a {@link RuleKeyCache} between builds. */
public class RuleKeyCacheRecycler<V> {

  private static final Logger LOG = Logger.get(RuleKeyCacheRecycler.class);

  private final TrackableRuleKeyCache<V> cache;
  private final ImmutableSet<ProjectFilesystem> watchedFilesystems;

  @Nullable private SettingsAffectingCache previousSettings = null;

  private RuleKeyCacheRecycler(
      TrackableRuleKeyCache<V> cache, ImmutableSet<ProjectFilesystem> watchedFilesystems) {
    this.cache = cache;
    this.watchedFilesystems = watchedFilesystems;
  }

  /**
   * @param eventBus {@link EventBus} which delivers watchman events.
   * @param watchedFilesystems all {@link ProjectFilesystem}s which use watchman to receive events
   *     when files are changed.
   * @return a new {@link RuleKeyCacheRecycler}.
   */
  public static <V> RuleKeyCacheRecycler<V> createAndRegister(
      EventBus eventBus,
      TrackableRuleKeyCache<V> ruleKeyCache,
      ImmutableSet<ProjectFilesystem> watchedFilesystems) {

    RuleKeyCacheRecycler<V> recycler = new RuleKeyCacheRecycler<>(ruleKeyCache, watchedFilesystems);

    // Subscribe the recycler to receive filesystem watch events.
    eventBus.register(recycler);

    return recycler;
  }

  public static <V> RuleKeyCacheRecycler<V> create(TrackableRuleKeyCache<V> ruleKeyCache) {
    return new RuleKeyCacheRecycler<>(ruleKeyCache, ImmutableSet.of());
  }

  @Subscribe
  public void onFilesystemChange(WatchmanPathEvent event) {
    // Currently, `WatchEvent`s only contain cell-relative paths, so we have no way of associating
    // them with a specific filesystem.  So, we assume the event can refer to any of the watched
    // filesystems and forward invalidations to all of them.
    for (ProjectFilesystem filesystem : watchedFilesystems) {
      Path path = event.getPath().normalize();
      LOG.verbose(
          "invalidating path \"%s\" from filesystem at \"%s\" due to event (%s)",
          path, filesystem.getRootPath(), event);
      invalidatePath(filesystem, path);
    }
  }

  public void invalidatePath(ProjectFilesystem filesystem, Path path) {
    cache.invalidateInputs(
        // As inputs to rule keys can be directories, make sure we also invalidate any
        // directories containing this path.
        IntStream.range(1, path.getNameCount() + 1)
            .mapToObj(end -> RuleKeyInput.of(filesystem, path.subpath(0, end)))
            .collect(ImmutableList.toImmutableList()),
        new NoOpCacheStatsTracker());
  }

  @Subscribe
  public void onFilesystemChange(WatchmanOverflowEvent event) {
    for (ProjectFilesystem filesystem : watchedFilesystems) {
      LOG.verbose(
          "invalidating filesystem at \"%s\" due to event (%s)", filesystem.getRootPath(), event);
      // Do not track stats from Daemon watchman events.
      cache.invalidateFilesystem(filesystem, new NoOpCacheStatsTracker());
    }
  }

  /**
   * Provides access to a {@link RuleKeyCache} via a {@link RuleKeyCacheScope}. The {@link
   * RuleKeyCacheScope} must be used with a try-resource block and does logging and cache
   * invalidation both before and after being used.
   *
   * @return a {@link RuleKeyCacheScope} managing access to enclosed {@link RuleKeyCache}.
   */
  public RuleKeyCacheScope<V> withRecycledCache(
      BuckEventBus buckEventBus, SettingsAffectingCache currentSettings) {
    return new EventPostingRuleKeyCacheScope<V>(
        buckEventBus, new TrackedRuleKeyCache<>(cache, new InstrumentingCacheStatsTracker())) {

      // Cache setup which is run before the caller gets access to the cache, at the time the scope
      // is allocated.
      @Override
      protected void setup(SimplePerfEvent.Scope scope) {
        super.setup(scope);

        // We invalidate everything if any of the settings we care about change.
        if (!SettingsAffectingCache.areIdentical(previousSettings, currentSettings)) {
          LOG.debug("invalidating entire cache due to settings change");
          getCache().invalidateAll();
          scope.update("settings_change", true);
        } else {
          scope.update("settings_change", false);
        }

        // Record the current settings for next time.
        previousSettings = currentSettings;
      }

      // Cache cleanup which is run after the caller is finished using the cache, at the conclusion
      // of the try-resource block that wraps the scope object.
      @Override
      protected void cleanup(SimplePerfEvent.Scope scope) {
        super.cleanup(scope);

        // Invalidate all rule keys transitively built from non-watched filesystems, as we have no
        // way of knowing which, if any, of its files have been modified/removed.
        LOG.verbose(
            "invalidating unwatched filesystems (everything except %s)", watchedFilesystems);
        getCache().invalidateAllExceptFilesystems(watchedFilesystems);
      }
    };
  }

  /**
   * Run the given {@link Consumer} with access to the {@link RuleKeyCache}. This is a convenience
   * method used to abstract away handling of the {@link RuleKeyCacheScope} inside a try-resource
   * block.
   */
  void withRecycledCache(
      BuckEventBus buckEventBus,
      SettingsAffectingCache currentSettings,
      Consumer<TrackedRuleKeyCache<V>> func) {
    try (RuleKeyCacheScope<V> scope = withRecycledCache(buckEventBus, currentSettings)) {
      func.accept(scope.getCache());
    }
  }

  public ImmutableList<Map.Entry<BuildRule, V>> getCachedBuildRules() {
    return cache.getCachedBuildRules();
  }

  /** Any external settings which, if changed, will cause the entire cache to be invalidated. */
  public static class SettingsAffectingCache {

    private final int ruleKeySeed;
    private final ActionGraph actionGraph;

    public SettingsAffectingCache(int ruleKeySeed, ActionGraph actionGraph) {
      this.ruleKeySeed = ruleKeySeed;
      this.actionGraph = actionGraph;
    }

    private static boolean areIdentical(
        @Nullable SettingsAffectingCache previous, SettingsAffectingCache current) {

      // If previous settings are null, then require an invalidation.
      if (previous == null) {
        return false;
      }

      if (previous.ruleKeySeed != current.ruleKeySeed) {
        return false;
      }

      // NOTE: Since the cache indexes using instance equality, it's only ever useful if we get a
      // hit in the action graph cache and re-use the same action graph in the next build.  So, if
      // we detect that a fresh action graph is being used, we eagerly dump the cache to free up
      // memory.
      return previous.actionGraph == current.actionGraph;
    }
  }
}
