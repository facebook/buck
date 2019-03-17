/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.query;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import javax.annotation.Nullable;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractQuery {

  abstract String getQuery();

  abstract TargetConfiguration getTargetConfiguration();

  abstract Optional<String> getBaseName();

  @Nullable
  @Value.NaturalOrder
  abstract ImmutableSortedSet<BuildTarget> getResolvedQuery();

  public static Query of(String query, TargetConfiguration targetConfiguration, String baseName) {
    return Query.of(query, targetConfiguration, Optional.of(baseName), null);
  }

  public static Query of(String query, TargetConfiguration targetConfiguration) {
    return Query.of(query, targetConfiguration, Optional.empty(), null);
  }
}
