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

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.Either;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.Objects;

/**
 * Represents a layered Xcode build configuration.
 *
 * The first layer is the lowest layer on the list, that is, the latter layers are visited first
 * when resolving variables.
 */
public class XcodeRuleConfiguration {
  private final ImmutableList<Layer> layers;

  public XcodeRuleConfiguration(ImmutableList<Layer> layers) {
    this.layers = Preconditions.checkNotNull(layers);
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
    return Objects.equals(layers, that.layers);
  }

  @Override
  public int hashCode() {
    return Objects.hash(layers);
  }

  /**
   * Convert from a raw json representation of the Configuration to an actual Configuration object.
   *
   * @param configurations
   *    A map of configuration names to lists, where each each element is a layer of the
   *    configuration. Each layer can be specified as a path to a .xcconfig file, or a dictionary of
   *    xcode build settings.
   */
  public static ImmutableMap<String, XcodeRuleConfiguration> fromRawJsonStructure(
      ImmutableMap<
          String,
          ImmutableList<Either<SourcePath, ImmutableMap<String, String>>>> configurations) {
    ImmutableMap.Builder<String, XcodeRuleConfiguration> builder = ImmutableMap.builder();
    for (ImmutableMap.Entry<
        String,
        ImmutableList<Either<SourcePath, ImmutableMap<String, String>>>> entry
        : configurations.entrySet()) {
      ImmutableList.Builder<Layer> layers = ImmutableList.builder();
      for (Either<SourcePath, ImmutableMap<String, String>> value : entry.getValue()) {
        if (value.isLeft()) {
          layers.add(new Layer(value.getLeft()));
        } else if (value.isRight()) {
          layers.add(new Layer(value.getRight()));
        }
      }
      builder.put(entry.getKey(), new XcodeRuleConfiguration(layers.build()));
    }
    return builder.build();
  }

  public static enum LayerType {
    FILE,
    INLINE_SETTINGS,
  }

  public static class Layer {
    private final LayerType layerType;
    private final Optional<SourcePath> sourcePath;
    private final Optional<ImmutableMap<String, String>> inlineSettings;

    public Layer(SourcePath path) {
      this.layerType = LayerType.FILE;
      this.sourcePath = Optional.of(Preconditions.checkNotNull(path));
      this.inlineSettings = Optional.absent();
    }

    public Layer(ImmutableMap<String, String> inlineSettings) {
      this.layerType = LayerType.INLINE_SETTINGS;
      this.sourcePath = Optional.absent();
      this.inlineSettings = Optional.of(Preconditions.checkNotNull(inlineSettings));
    }

    public LayerType getLayerType() {
      return layerType;
    }

    public Optional<SourcePath> getSourcePath() {
      return sourcePath;
    }

    public Optional<ImmutableMap<String, String>> getInlineSettings() {
      return inlineSettings;
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
      return Objects.equals(this.layerType, that.layerType) &&
          Objects.equals(this.sourcePath, that.sourcePath) &&
          Objects.equals(this.inlineSettings, that.inlineSettings);
    }

    @Override
    public int hashCode() {
      return Objects.hash(layerType, sourcePath, inlineSettings);
    }
  }
}
