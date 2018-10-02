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

package com.facebook.buck.cli;

import com.facebook.buck.core.config.AliasConfig;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

public class LabelSelector {
  private final boolean isInclusive;
  private final Set<String> labels;

  private static final Splitter splitter =
      Splitter.on(TestLabelOptions.LABEL_SEPERATOR).trimResults().omitEmptyStrings();

  static LabelSelector fromString(String raw) {
    Preconditions.checkState(!raw.isEmpty());

    boolean isInclusive = true;
    if (raw.charAt(0) == '!') {
      isInclusive = false;
      raw = raw.substring(1);
    }

    ImmutableSet.Builder<String> labelBuilder = new ImmutableSet.Builder<>();
    Iterable<String> labelStrings = splitter.split(raw);
    for (String labelString : labelStrings) {
      AliasConfig.validateLabelName(labelString);
      labelBuilder.add(labelString);
    }

    return new LabelSelector(isInclusive, labelBuilder.build());
  }

  LabelSelector(boolean isInclusive, Set<String> labels) {
    this.isInclusive = isInclusive;
    this.labels = labels;
  }

  public boolean matches(Set<String> rawLabels) {
    return rawLabels.containsAll(labels);
  }

  public boolean isInclusive() {
    return isInclusive;
  }

  public LabelSelector invert() {
    return new LabelSelector(!isInclusive, labels);
  }
}
