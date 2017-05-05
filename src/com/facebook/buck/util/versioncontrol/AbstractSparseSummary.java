/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.versioncontrol;

import com.facebook.buck.log.views.JsonViews;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.immutables.value.Value;

@Value.Immutable(copy = false, singleton = true)
@BuckStyleTuple
@JsonSerialize(as = SparseSummary.class)
@JsonDeserialize(as = SparseSummary.class)
// Mercurial outputs snake_case attributes. Rather than create a new object mapper just for that
// format, just set a naming strategy for both serialization and deserialization.
@JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy.class)
public abstract class AbstractSparseSummary {
  @Value.Default
  @JsonView(JsonViews.MachineReadableLog.class)
  public int getProfilesAdded() {
    return 0;
  }

  @Value.Default
  @JsonView(JsonViews.MachineReadableLog.class)
  public int getIncludeRulesAdded() {
    return 0;
  }

  @Value.Default
  @JsonView(JsonViews.MachineReadableLog.class)
  public int getExcludeRulesAdded() {
    return 0;
  }

  @Value.Default
  @JsonView(JsonViews.MachineReadableLog.class)
  public int getFilesAdded() {
    return 0;
  }

  @Value.Default
  @JsonView(JsonViews.MachineReadableLog.class)
  public int getFilesDropped() {
    return 0;
  }

  @Value.Default
  @JsonView(JsonViews.MachineReadableLog.class)
  public int getFilesConflicting() {
    return 0;
  }

  public SparseSummary combineSummaries(SparseSummary other) {
    return SparseSummary.of(
        getProfilesAdded() + other.getProfilesAdded(),
        getIncludeRulesAdded() + other.getIncludeRulesAdded(),
        getExcludeRulesAdded() + other.getExcludeRulesAdded(),
        getFilesAdded() + other.getFilesAdded(),
        getFilesDropped() + other.getFilesDropped(),
        getFilesConflicting() + other.getFilesConflicting());
  }
}
