/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import java.util.Objects;

/**
 * Represents a layer in an Xcode build configuration.
 */
public class XcodeRuleConfigurationLayer {
  public static enum TYPE {
    FILE,
    INLINE_SETTINGS,
  }

  private final TYPE layerType;
  private final Optional<SourcePath> sourcePath;
  private final Optional<ImmutableMap<String, String>> inlineSettings;

  public XcodeRuleConfigurationLayer(SourcePath path) {
    this.layerType = TYPE.FILE;
    this.sourcePath = Optional.of(path);
    this.inlineSettings = Optional.absent();
  }

  public XcodeRuleConfigurationLayer(ImmutableMap<String, String> inlineSettings) {
    this.layerType = TYPE.INLINE_SETTINGS;
    this.sourcePath = Optional.absent();
    this.inlineSettings = Optional.of(inlineSettings);
  }

  public TYPE getLayerType() {
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
    if (!(o instanceof XcodeRuleConfigurationLayer)) {
      return false;
    }

    XcodeRuleConfigurationLayer that = (XcodeRuleConfigurationLayer) o;
    return Objects.equals(this.layerType, that.layerType) &&
        Objects.equals(this.sourcePath, that.sourcePath) &&
        Objects.equals(this.inlineSettings, that.inlineSettings);
  }

  @Override
  public int hashCode() {
    return Objects.hash(layerType, sourcePath, inlineSettings);
  }
}
