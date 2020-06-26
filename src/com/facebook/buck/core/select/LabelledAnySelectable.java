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

package com.facebook.buck.core.select;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableMap;

/**
 * Selectable-like object which matches any of contained selectables. Unlike {@link AnySelectable}
 * this contains labels for any variants.
 */
@BuckStyleValue
public abstract class LabelledAnySelectable {
  /** Object key is a label; printable with {@code toString}. */
  public abstract ImmutableMap<Object, ConfigSettingSelectable> getSelectables();

  /** @return <code>true</code> if this condition matches the platform. */
  public boolean matchesPlatform(
      Platform platform, BuckConfig buckConfig, DependencyStack dependencyStack) {

    // Optimization
    if (this == any()) {
      return true;
    }

    return getSelectables().values().stream()
        .anyMatch(s -> s.matchesPlatform(platform, buckConfig, dependencyStack));
  }

  /** Constructor. */
  public static LabelledAnySelectable of(
      ImmutableMap<UnflavoredBuildTarget, ConfigSettingSelectable> selectables) {
    return ImmutableLabelledAnySelectable.ofImpl(selectables);
  }

  private static class AnyHolder {
    private static final LabelledAnySelectable ANY =
        ImmutableLabelledAnySelectable.ofImpl(
            ImmutableMap.of("<any>", ConfigSettingSelectable.any()));
    private static final LabelledAnySelectable NONE =
        ImmutableLabelledAnySelectable.ofImpl(ImmutableMap.of());
  }

  public static LabelledAnySelectable any() {
    return LabelledAnySelectable.AnyHolder.ANY;
  }

  public static LabelledAnySelectable none() {
    return LabelledAnySelectable.AnyHolder.NONE;
  }
}
