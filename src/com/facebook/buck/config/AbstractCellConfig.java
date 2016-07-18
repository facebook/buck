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
package com.facebook.buck.config;

import com.facebook.buck.rules.RelativeCellName;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Hierarcical configuration of cell/section/key/value quadruples.
 *
 * This class only implements the simple construction/storage/retrieval of these values. Other
 * classes like {@link Config} implements accessors that interpret the values as other types.
 */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractCellConfig {
  public static final RelativeCellName ALL_CELLS_OVERRIDE =
      RelativeCellName.of(ImmutableSet.of(RelativeCellName.ALL_CELLS_SPECIAL_NAME));

  public abstract ImmutableMap<RelativeCellName, ImmutableMap<String, ImmutableMap<String, String>>>
    getValues();

  /**
   * Retrieve the Cell-view of the raw config
   *
   * @return The contents of the raw config with the cell-view filter
   */
  public RawConfig getForCell(RelativeCellName cellName) {
    ImmutableMap<String, ImmutableMap<String, String>> config = Optional
      .fromNullable(getValues().get(cellName))
      .or(ImmutableMap.<String, ImmutableMap<String, String>>of());
    ImmutableMap<String, ImmutableMap<String, String>> starConfig = Optional
      .fromNullable(getValues().get(ALL_CELLS_OVERRIDE))
      .or(ImmutableMap.<String, ImmutableMap<String, String>>of());
    return RawConfig.builder()
      .putAll(starConfig)
      .putAll(config)
      .build();
  }

  /**
   * Translates the 'cell name'->override map into a 'Path'->override map.
   * @param pathMapping a map containing paths to all of the cells we want to query.
   * @return 'Path'->override map
   */
  public ImmutableMap<Path, RawConfig> getOverridesByPath(
      ImmutableMap<RelativeCellName, Path> pathMapping) throws MalformedOverridesException {

    ImmutableSet<RelativeCellName> relativeNamesOfCellsWithOverrides =
        FluentIterable.from(getValues().keySet())
            .filter(Predicates.not(Predicates.equalTo(ALL_CELLS_OVERRIDE)))
            .toSet();
    ImmutableSet.Builder<Path> pathsWithOverrides = ImmutableSet.builder();
    for (RelativeCellName cellWithOverride : relativeNamesOfCellsWithOverrides) {
      if (!pathMapping.containsKey(cellWithOverride)) {
        throw new MalformedOverridesException(
            String.format("Trying to override settings for unknown cell %s", cellWithOverride));
      }
      pathsWithOverrides.add(pathMapping.get(cellWithOverride));
    }


    ImmutableMultimap<Path, RelativeCellName> pathToRelativeName =
        Multimaps.index(pathMapping.keySet(), Functions.forMap(pathMapping));

    for (Path pathWithOverrides : pathsWithOverrides.build()) {
      ImmutableCollection<RelativeCellName> namesForPath = pathToRelativeName.get(
          pathWithOverrides);
      if (namesForPath.size() > 1) {
        throw new MalformedOverridesException(
            String.format("Configuration override is ambiguous: cell rooted at %s is reachable " +
                "as [%s]. Please override the config by placing a .buckconfig.local file in the " +
                "cell's root folder.",
                pathWithOverrides,
                Joiner.on(',').join(namesForPath)));
      }
    }

    Map<Path, RawConfig> overridesByPath = new HashMap<>();
    for (Map.Entry<RelativeCellName, Path> entry : pathMapping.entrySet()) {
      RelativeCellName cellRelativeName = entry.getKey();
      Path cellPath = entry.getValue();
      RawConfig configFromOtherRelativeName = overridesByPath.get(cellPath);
      RawConfig config = getForCell(cellRelativeName);
      if (configFromOtherRelativeName != null) {
        Preconditions.checkState(
            configFromOtherRelativeName.equals(config),
            "Attempting to create cell %s at %s with conflicting overrides [%s] vs [%s].",
            cellRelativeName,
            cellPath,
            configFromOtherRelativeName,
            config);
      } else {
        overridesByPath.put(cellPath, config);
      }
    }

    return ImmutableMap.copyOf(overridesByPath);
  }

  /**
   * Returns an empty config.
   */
  public static CellConfig of() {
    return CellConfig.of(
        ImmutableMap.<RelativeCellName, ImmutableMap<String, ImmutableMap<String, String>>>of());
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for {@link CellConfig}s.
   *
   * Unless otherwise stated, duplicate keys overwrites earlier ones.
   */
  public static class Builder {
    private Map<RelativeCellName, Map<String, Map<String, String>>> values =
        Maps.newLinkedHashMap();

    /**
     * Merge raw config values into this config.
     */
    public <M extends Map<String, String>> Builder putAll(
        RelativeCellName cellName, Map<String, M> config) {
      for (Map.Entry<String, M> entry : config.entrySet()) {
        requireSection(cellName, entry.getKey()).putAll(entry.getValue());
      }
      return this;
    }

    /**
     * Merge the values from another {@code RawConfig}.
     */
    public Builder putAll(RawConfig config) {
      return putAll(RelativeCellName.ROOT_CELL_NAME, config.getValues());
    }

    /**
     * Merge the values from another {@code RawConfig}.
     */
    public Builder putAll(RelativeCellName cell, RawConfig config) {
      return putAll(cell, config.getValues());
    }

    /**
     * Put a single value.
     */
    public Builder put(RelativeCellName cell, String section, String key, String value) {
      requireSection(cell, section).put(key, value);
      return this;
    }

    public CellConfig build() {
      ImmutableMap.Builder<RelativeCellName, ImmutableMap<String, ImmutableMap<String, String>>>
        builder = ImmutableMap.builder();
      for (RelativeCellName cell : values.keySet()) {
        ImmutableMap.Builder<String, ImmutableMap<String, String>> rawBuilder =
            ImmutableMap.builder();
        Map<String, Map<String, String>> config = values.get(cell);
        if (config == null) {
          continue;
        }
        for (Map.Entry<String, Map<String, String>> entry : config.entrySet()) {
          rawBuilder.put(entry.getKey(), ImmutableMap.copyOf(entry.getValue()));
        }
        builder.put(cell, rawBuilder.build());
      }
      return CellConfig.of(builder.build());
    }

    /**
     * Get a section or create it if it doesn't exist.
     */
    private Map<String, Map<String, String>> requireCell(RelativeCellName cellName) {
      Map<String, Map<String, String>> cell = values.get(cellName);
      if (cell == null) {
        cell = Maps.newLinkedHashMap();
        values.put(cellName, cell);
      }
      return cell;
    }

    /**
     * Get a section or create it if it doesn't exist.
     */
    private Map<String, String> requireSection(RelativeCellName cellName, String sectionName) {
      Map<String, Map<String, String>> cell = requireCell(cellName);
      Map<String, String> section = cell.get(sectionName);
      if (section == null) {
        section = Maps.newLinkedHashMap();
        cell.put(sectionName, section);
      }
      return section;
    }

  }

  public static class MalformedOverridesException extends Exception {
    public MalformedOverridesException(String message) {
      super(message);
    }
  }
}
