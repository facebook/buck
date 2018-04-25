/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.versions;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;

/**
 * A fast constraint resolver which selects versions using pre-defined version universes.
 *
 * <p>TODO(agallagher): Validate version constraints.
 */
public class VersionUniverseVersionSelector implements VersionSelector {

  private static final Logger LOG = Logger.get(VersionUniverseVersionSelector.class);

  private final TargetGraph targetGraph;
  private final ImmutableMap<String, VersionUniverse> universes;

  public VersionUniverseVersionSelector(
      TargetGraph targetGraph, ImmutableMap<String, VersionUniverse> universes) {
    this.targetGraph = targetGraph;
    this.universes = universes;
  }

  private <A> Optional<String> getVersionUniverseName(TargetNode<A, ?> root) {
    A arg = root.getConstructorArg();
    if (arg instanceof HasVersionUniverse) {
      return ((HasVersionUniverse) arg).getVersionUniverse();
    }
    return Optional.empty();
  }

  @VisibleForTesting
  protected Optional<Map.Entry<String, VersionUniverse>> getVersionUniverse(TargetNode<?, ?> root) {
    Optional<String> universeName = getVersionUniverseName(root);
    if (!universeName.isPresent() && !universes.isEmpty()) {
      return Optional.of(Iterables.get(universes.entrySet(), 0));
    }
    if (!universeName.isPresent()) {
      return Optional.empty();
    }
    VersionUniverse universe = universes.get(universeName.get());
    if (universe == null) {
      throw new VerifyException(
          String.format(
              "%s: unknown version universe \"%s\"", root.getBuildTarget(), universeName.get()));
    }
    return Optional.of(new AbstractMap.SimpleEntry<>(universeName.get(), universe));
  }

  private ImmutableMap<BuildTarget, Version> selectVersions(
      BuildTarget root, ImmutableMap<BuildTarget, ImmutableSet<Version>> domain)
      throws VersionException {

    TargetNode<?, ?> node = targetGraph.get(root);
    ImmutableMap.Builder<BuildTarget, Version> selectedVersions = ImmutableMap.builder();

    Optional<Map.Entry<String, VersionUniverse>> universe = getVersionUniverse(node);
    LOG.verbose("%s: selected universe: %s", root, universe);
    for (Map.Entry<BuildTarget, ImmutableSet<Version>> ent : domain.entrySet()) {
      Version version;
      if (universe.isPresent()
          && ((version = universe.get().getValue().getVersions().get(ent.getKey())) != null)) {
        if (!ent.getValue().contains(version)) {
          throw new VersionException(
              root,
              String.format(
                  "%s has no version %s (specified by universe %s) in available versions: %s",
                  ent.getKey(),
                  version,
                  universe.get().getKey(),
                  Joiner.on(", ").join(ent.getValue())));
        }
      } else {
        version = Iterables.get(ent.getValue(), 0);
      }
      selectedVersions.put(ent.getKey(), version);
    }

    return selectedVersions.build();
  }

  @Override
  public ImmutableMap<BuildTarget, Version> resolve(
      BuildTarget root, ImmutableMap<BuildTarget, ImmutableSet<Version>> domain)
      throws VersionException {
    return selectVersions(root, domain);
  }
}
