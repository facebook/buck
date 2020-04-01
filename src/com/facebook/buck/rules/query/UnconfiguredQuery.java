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

package com.facebook.buck.rules.query;

import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** Unconfigured version of {@link com.facebook.buck.rules.query.Query}. */
@BuckStyleValue
public abstract class UnconfiguredQuery {
  public abstract String getQuery();

  public abstract BaseName getBaseName();

  /** Apply configuration. */
  public Query configure(TargetConfiguration targetConfiguration) {
    return Query.of(getQuery(), targetConfiguration, getBaseName());
  }

  /** Constructor. */
  public static UnconfiguredQuery of(String query, BaseName baseName) {
    return ImmutableUnconfiguredQuery.ofImpl(query, baseName);
  }
}
