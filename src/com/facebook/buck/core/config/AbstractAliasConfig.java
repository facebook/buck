/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.config;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.immutables.value.Value;

/** Contains the logic for handling the [alias] section of .buckconfig. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractAliasConfig implements ConfigView<BuckConfig> {
  private static final String SECTION = "alias";

  public static AliasConfig from(BuckConfig config) {
    return config.getView(AliasConfig.class);
  }

  /**
   * This pattern is designed so that a fully-qualified build target cannot be a valid alias name
   * and vice-versa.
   */
  private static final Pattern ALIAS_PATTERN = Pattern.compile("[a-zA-Z_-][a-zA-Z0-9_-]*");

  public ImmutableMap<String, String> getEntries() {
    return getDelegate().getEntriesForSection(SECTION);
  }

  @Value.Lazy
  public ImmutableSetMultimap<String, BuildTarget> getAliases() {
    return createAliasToBuildTargetMap(getEntries());
  }

  public ImmutableSet<String> getBuildTargetForAliasAsString(String possiblyFlavoredAlias) {
    String[] parts = possiblyFlavoredAlias.split("#", 2);
    String unflavoredAlias = parts[0];
    ImmutableSet<BuildTarget> buildTargets = getBuildTargetsForAlias(unflavoredAlias);
    if (buildTargets.isEmpty()) {
      return ImmutableSet.of();
    }
    String suffix = parts.length == 2 ? "#" + parts[1] : "";
    return buildTargets
        .stream()
        .map(buildTarget -> buildTarget.getFullyQualifiedName() + suffix)
        .collect(ImmutableSet.toImmutableSet());
  }

  public ImmutableSet<BuildTarget> getBuildTargetsForAlias(String unflavoredAlias) {
    return getAliases().get(unflavoredAlias);
  }

  public static void validateAliasName(String aliasName) throws HumanReadableException {
    validateAgainstAlias(aliasName, "Alias");
  }

  public static void validateLabelName(String aliasName) throws HumanReadableException {
    validateAgainstAlias(aliasName, "Label");
  }

  /**
   * Create a map of {@link BuildTarget} base paths to aliases. Note that there may be more than one
   * alias to a base path, so the first one listed in the .buckconfig will be chosen.
   */
  public ImmutableMap<Path, String> getBasePathToAliasMap() {
    ImmutableMap<String, String> aliases = getEntries();
    if (aliases.isEmpty()) {
      return ImmutableMap.of();
    }

    // Build up the Map with an ordinary HashMap because we need to be able to check whether the Map
    // already contains the key before inserting.
    Map<Path, String> basePathToAlias = new HashMap<>();
    for (Map.Entry<String, BuildTarget> entry : getAliases().entries()) {
      String alias = entry.getKey();
      BuildTarget buildTarget = entry.getValue();

      Path basePath = buildTarget.getBasePath();
      if (!basePathToAlias.containsKey(basePath)) {
        basePathToAlias.put(basePath, alias);
      }
    }
    return ImmutableMap.copyOf(basePathToAlias);
  }

  /**
   * In a {@link BuckConfig}, an alias can either refer to a fully-qualified build target, or an
   * alias defined earlier in the {@code alias} section. The mapping produced by this method
   * reflects the result of resolving all aliases as values in the {@code alias} section.
   */
  private ImmutableSetMultimap<String, BuildTarget> createAliasToBuildTargetMap(
      ImmutableMap<String, String> rawAliasMap) {
    // We use a LinkedHashMap rather than an ImmutableMap.Builder because we want both (1) order to
    // be preserved, and (2) the ability to inspect the Map while building it up.
    SetMultimap<String, BuildTarget> aliasToBuildTarget = LinkedHashMultimap.create();
    for (Map.Entry<String, String> aliasEntry : rawAliasMap.entrySet()) {
      String alias = aliasEntry.getKey();
      validateAliasName(alias);

      // Determine whether the mapping is to a build target or to an alias.
      List<String> values = Splitter.on(' ').splitToList(aliasEntry.getValue());
      for (String value : values) {
        Set<BuildTarget> buildTargets;
        if (isValidAliasName(value)) {
          buildTargets = aliasToBuildTarget.get(value);
          if (buildTargets.isEmpty()) {
            throw new HumanReadableException("No alias for: %s.", value);
          }
        } else if (value.isEmpty()) {
          continue;
        } else {
          // Here we parse the alias values with a BuildTargetParser to be strict. We could be
          // looser and just grab everything between "//" and ":" and assume it's a valid base path.
          buildTargets =
              ImmutableSet.of(getDelegate().getBuildTargetForFullyQualifiedTarget(value));
        }
        aliasToBuildTarget.putAll(alias, buildTargets);
      }
    }
    return ImmutableSetMultimap.copyOf(aliasToBuildTarget);
  }

  /**
   * @return whether {@code aliasName} conforms to the pattern for a valid alias name. This does not
   *     indicate whether it is an alias that maps to a build target in a BuckConfig.
   */
  private static boolean isValidAliasName(String aliasName) {
    return ALIAS_PATTERN.matcher(aliasName).matches();
  }

  private static void validateAgainstAlias(String aliasName, String fieldName) {
    if (isValidAliasName(aliasName)) {
      return;
    }

    if (aliasName.isEmpty()) {
      throw new HumanReadableException("%s cannot be the empty string.", fieldName);
    }

    throw new HumanReadableException("Not a valid %s: %s.", fieldName.toLowerCase(), aliasName);
  }
}
