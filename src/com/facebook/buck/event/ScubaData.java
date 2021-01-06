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

package com.facebook.buck.event;

import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;

@BuckStyleValueWithBuilder
@JsonSerialize(as = ImmutableScubaData.class)
@JsonDeserialize(as = ImmutableScubaData.class)
public abstract class ScubaData {

  public abstract ImmutableMap<String, Long> getInts();

  public abstract ImmutableMap<String, String> getNormals();

  public abstract ImmutableSetMultimap<String, String> getTags();

  public static void addScubaData(Builder builder, ScubaData data) {
    builder.putAllInts(data.getInts());
    builder.putAllNormals(data.getNormals());
    builder.putAllTags(data.getTags());
  }

  public static Builder builder(ScubaData data) {
    Builder builder = new Builder();
    addScubaData(builder, data);
    return builder;
  }

  public static Builder builder() {
    Builder builder = new Builder();
    return builder;
  }

  public static ScubaData of() {
    return builder().build();
  }

  public static class Builder extends ImmutableScubaData.Builder {}
}
