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
package com.facebook.buck.core.cell;

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.RawConfig;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimaps;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.immutables.value.Value;

/**
 * Hierarcical configuration of cell/section/key/value quadruples.
 *
 * <p>This class only implements the simple construction/storage/retrieval of these values. Other
 * classes like {@link Config} implements accessors that interpret the values as other types.
 */
@Value.Immutable(singleton = true, builder = false, copy = false)
@BuckStyleTuple
abstract class AbstractCellConfig {
  public abstract ImmutableMap<CellName, RawConfig> getValues();

  /**
   * Retrieve the Cell-view of the raw config.
   *
   * @return The contents of the raw config with the cell-view filter
   */
  public RawConfig getForCell(CellName cellName) {
    RawConfig config = Optional.ofNullable(getValues().get(cellName)).orElse(RawConfig.of());
    RawConfig starConfig =
        Optional.ofNullable(getValues().get(CellName.ALL_CELLS_SPECIAL_NAME))
            .orElse(RawConfig.of());
    return RawConfig.builder().putAll(starConfig).putAll(config).build();
  }

  /**
   * Translates the 'cell name'->override map into a 'Path'->override map.
   *
   * @param pathMapping a map containing paths to all of the cells we want to query.
   * @return 'Path'->override map
   */
  public ImmutableMap<Path, RawConfig> getOverridesByPath(ImmutableMap<CellName, Path> pathMapping)
      throws InvalidCellOverrideException {

    ImmutableSet<CellName> relativeNamesOfCellsWithOverrides =
        FluentIterable.from(getValues().keySet())
            .filter(Predicates.not(CellName.ALL_CELLS_SPECIAL_NAME::equals))
            .toSet();
    ImmutableSet.Builder<Path> pathsWithOverrides = ImmutableSet.builder();
    for (CellName cellWithOverride : relativeNamesOfCellsWithOverrides) {
      if (!pathMapping.containsKey(cellWithOverride)) {
        throw new InvalidCellOverrideException(
            String.format("Trying to override settings for unknown cell %s", cellWithOverride));
      }
      pathsWithOverrides.add(pathMapping.get(cellWithOverride));
    }

    ImmutableMultimap<Path, CellName> pathToRelativeName =
        Multimaps.index(pathMapping.keySet(), Functions.forMap(pathMapping));

    for (Path pathWithOverrides : pathsWithOverrides.build()) {
      ImmutableList<CellName> namesForPath =
          RichStream.from(pathToRelativeName.get(pathWithOverrides))
              .filter(name -> name.getLegacyName().isPresent())
              .toImmutableList();
      if (namesForPath.size() > 1) {
        throw new InvalidCellOverrideException(
            String.format(
                "Configuration override is ambiguous: cell rooted at %s is reachable "
                    + "as [%s]. Please override the config by placing a .buckconfig.local file in the "
                    + "cell's root folder.",
                pathWithOverrides, Joiner.on(',').join(namesForPath)));
      }
    }

    Map<Path, RawConfig> overridesByPath = new HashMap<>();
    for (Map.Entry<CellName, Path> entry : pathMapping.entrySet()) {
      CellName cellRelativeName = entry.getKey();
      Path cellPath = entry.getValue();
      RawConfig configFromOtherRelativeName = overridesByPath.get(cellPath);
      RawConfig config = getForCell(cellRelativeName);
      if (configFromOtherRelativeName != null) {
        // Merge configs
        RawConfig mergedConfig =
            RawConfig.builder().putAll(configFromOtherRelativeName).putAll(config).build();
        overridesByPath.put(cellPath, mergedConfig);
      } else {
        overridesByPath.put(cellPath, config);
      }
    }

    return ImmutableMap.copyOf(overridesByPath);
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for {@link CellConfig}s.
   *
   * <p>Unless otherwise stated, duplicate keys overwrites earlier ones.
   */
  public static class Builder {
    private Map<CellName, RawConfig.Builder> values = new LinkedHashMap<>();

    /** Put a single value. */
    public Builder put(CellName cell, String section, String key, String value) {
      requireCell(cell).put(section, key, value);
      return this;
    }

    public CellConfig build() {
      return values.entrySet().stream()
          .collect(
              Collectors.collectingAndThen(
                  ImmutableMap.toImmutableMap(e -> e.getKey(), entry -> entry.getValue().build()),
                  CellConfig::of));
    }

    /** Get a section or create it if it doesn't exist. */
    private RawConfig.Builder requireCell(CellName cellName) {
      RawConfig.Builder cell = values.get(cellName);
      if (cell == null) {
        cell = RawConfig.builder();
        values.put(cellName, cell);
      }
      return cell;
    }
  }
}
