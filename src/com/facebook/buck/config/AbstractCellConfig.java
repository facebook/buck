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

import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import org.immutables.value.Value;

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
  public abstract ImmutableMap<Optional<String>, ImmutableMap<String, ImmutableMap<String, String>>>
    getValues();

  /**
   * Retrieve the Cell-view of the raw config
   *
   * @return The contents of the raw config with the cell-view filter
   */
  public RawConfig getForCell(Optional<String> cellName) {
    ImmutableMap<String, ImmutableMap<String, String>> config = Optional
      .fromNullable(getValues().get(cellName))
      .or(ImmutableMap.<String, ImmutableMap<String, String>>of());
    ImmutableMap<String, ImmutableMap<String, String>> starConfig = Optional
      .fromNullable(getValues().get(Optional.of("*")))
      .or(ImmutableMap.<String, ImmutableMap<String, String>>of());
    return RawConfig.builder()
      .putAll(starConfig)
      .putAll(config)
      .build();
  }

  /**
   * Returns an empty config.
   */
  public static CellConfig of() {
    return CellConfig.of(
        ImmutableMap.<Optional<String>, ImmutableMap<String, ImmutableMap<String, String>>>of());
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
    private Map<Optional<String>, Map<String, Map<String, String>>> values =
        Maps.newLinkedHashMap();

    /**
     * Merge raw config values into this config.
     */
    public <M extends Map<String, String>> Builder putAll(
        Optional<String> cellName, Map<String, M> config) {
      for (Map.Entry<String, M> entry : config.entrySet()) {
        requireSection(cellName, entry.getKey()).putAll(entry.getValue());
      }
      return this;
    }

    /**
     * Merge the values from another {@code RawConfig}.
     */
    public Builder putAll(RawConfig config) {
      return putAll(Optional.<String>absent(), config.getValues());
    }

    /**
     * Merge the values from another {@code RawConfig}.
     */
    public Builder putAll(Optional<String> cell, RawConfig config) {
      return putAll(cell, config.getValues());
    }

    /**
     * Put a single value.
     */
    public Builder put(Optional<String> cell, String section, String key, String value) {
      requireSection(cell, section).put(key, value);
      return this;
    }

    public CellConfig build() {
      ImmutableMap.Builder<Optional<String>, ImmutableMap<String, ImmutableMap<String, String>>>
        builder = ImmutableMap.builder();
      for (Optional<String> cell : values.keySet()) {
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
    private Map<String, Map<String, String>> requireCell(Optional<String> cellName) {
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
    private Map<String, String> requireSection(Optional<String> cellName, String sectionName) {
      Map<String, Map<String, String>> cell = requireCell(cellName);
      Map<String, String> section = cell.get(sectionName);
      if (section == null) {
        section = Maps.newLinkedHashMap();
        cell.put(sectionName, section);
      }
      return section;
    }

  }

}
