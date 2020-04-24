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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.param.ParamName;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Comparator;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** Rule parameters list/map. */
@BuckStyleValue
public abstract class ParamsInfo {
  public abstract ImmutableList<ParamInfo<?>> getParamInfosSorted();

  @Value.Derived
  public ImmutableMap<String, ParamInfo<?>> getParamInfosByCamelCaseName() {
    return getParamInfosSorted().stream()
        .collect(ImmutableMap.toImmutableMap(p -> p.getName().getCamelCase(), p -> p));
  }

  @Value.Derived
  public ImmutableMap<String, ParamInfo<?>> getParamInfosByStarlarkName() {
    return getParamInfosSorted().stream()
        .collect(ImmutableMap.toImmutableMap(p -> p.getName().getSnakeCase(), p -> p));
  }

  @Value.Derived
  public ImmutableMap<ParamName, ParamInfo<?>> getParamInfosByName() {
    return getParamInfosSorted().stream()
        .collect(ImmutableMap.toImmutableMap(ParamInfo::getName, p -> p));
  }

  @Value.Derived
  public ImmutableList<String> getParamStarlarkNames() {
    return getParamInfosByStarlarkName().keySet().stream()
        .sorted(ParamInfo.NAME_COMPARATOR)
        .collect(ImmutableList.toImmutableList());
  }

  @Nullable
  public ParamInfo<?> getByStarlarkName(String name) {
    return getParamInfosByStarlarkName().get(name);
  }

  @Nullable
  public ParamInfo<?> getByName(ParamName name) {
    return getParamInfosByName().get(name);
  }

  /** Construct a new immutable {@code ParamInfos} instance. */
  public static ParamsInfo of(ImmutableList<ParamInfo<?>> paramInfos) {
    return ImmutableParamsInfo.ofImpl(
        paramInfos.stream()
            .sorted(
                Comparator.comparing(p -> p.getName().getSnakeCase(), ParamInfo.NAME_COMPARATOR))
            .collect(ImmutableList.toImmutableList()));
  }
}
