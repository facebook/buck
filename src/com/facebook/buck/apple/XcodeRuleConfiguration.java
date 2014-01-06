/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.rules.coercer.Either;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * Represents a layered Xcode build configuration.
 *
 * The first layer is the lowest layer on the list, that is, the latter layers are visited first
 * when resolving variables.
 */
public class XcodeRuleConfiguration {
  private final String name;
  private final ImmutableList<Layer> layers;

  public XcodeRuleConfiguration(
      String name,
      ImmutableList<Layer> layers) {
    this.name = Preconditions.checkNotNull(name);
    this.layers = Preconditions.checkNotNull(layers);
  }

  public String getName() {
    return name;
  }

  public ImmutableList<Layer> getLayers() {
    return layers;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof XcodeRuleConfiguration)) {
      return false;
    }

    XcodeRuleConfiguration that = (XcodeRuleConfiguration) o;
    return Objects.equals(name, that.name)
        && Objects.equals(layers, that.layers);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, layers);
  }

  /**
   * Convert from a raw json representation of the Configuration to an actual Configuration object.
   *
   * @param configurations
   *    A map of configuration names to lists, where each each element is a layer of the
   *    configuration. Each layer can be specified as a path to a .xcconfig file, or a dictionary of
   *    xcode build settings.
   */
  public static ImmutableSet<XcodeRuleConfiguration> fromRawJsonStructure(
      ImmutableMap<
          String,
          ImmutableList<Either<Path, ImmutableMap<String, String>>>> configurations) {
    ImmutableSet.Builder<XcodeRuleConfiguration> builder = ImmutableSet.builder();
    for (ImmutableMap.Entry<String, ImmutableList<Either<Path, ImmutableMap<String, String>>>> entry
        : configurations.entrySet()) {
      ImmutableList.Builder<Layer> layers = ImmutableList.builder();
      for (Either<Path, ImmutableMap<String, String>> value : entry.getValue()) {
        if (value.isLeft()) {
          layers.add(new Layer(value.getLeft()));
        } else if (value.isRight()) {
          layers.add(new Layer(value.getRight()));
        }
      }
      builder.add(new XcodeRuleConfiguration(entry.getKey(), layers.build()));
    }
    return builder.build();
  }

  public static enum LayerType {
    FILE,
    INLINE_SETTINGS,
  }

  public static class Layer {
    private final LayerType layerType;
    @Nullable private final Path path;
    @Nullable private final ImmutableMap<String, String> inlineSettings;

    public Layer(Path path) {
      this.layerType = LayerType.FILE;
      this.path = Preconditions.checkNotNull(path);
      this.inlineSettings = null;
    }

    public Layer(ImmutableMap<String, String> inlineSettings) {
      this.layerType = LayerType.INLINE_SETTINGS;
      this.path = null;
      this.inlineSettings = Preconditions.checkNotNull(inlineSettings);
    }

    public LayerType getLayerType() {
      return layerType;
    }

    public Optional<Path> getPath() {
      return layerType == LayerType.FILE ? Optional.of(path) : Optional.<Path>absent();
    }

    public Optional<ImmutableMap<String, String>> getInlineSettings() {
      return layerType == LayerType.INLINE_SETTINGS
          ? Optional.of(inlineSettings)
          : Optional.<ImmutableMap<String, String>>absent();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Layer)) {
        return false;
      }

      Layer that = (Layer) o;
      return Objects.equals(this.layerType, that.layerType)
          && Objects.equals(this.path, that.path)
          && Objects.equals(this.inlineSettings, that.inlineSettings);
    }

    @Override
    public int hashCode() {
      return Objects.hash(layerType, path, inlineSettings);
    }
  }
}
