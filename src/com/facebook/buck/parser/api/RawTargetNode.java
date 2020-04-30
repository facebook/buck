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

package com.facebook.buck.parser.api;

import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.rules.visibility.VisibilityAttributes;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.Map;
import javax.annotation.Nullable;

/** What we got from Starlark/Python interpreter. */
@BuckStyleValue
public abstract class RawTargetNode {
  public abstract ForwardRelativePath getBasePath();

  public abstract String getBuckType();

  public abstract ImmutableList<String> getVisibility();

  public abstract ImmutableList<String> getWithinView();

  public abstract TwoArraysImmutableHashMap<ParamName, Object> getAttrs();

  /** Get attributes including special attributes not present in {@link #getAttrs()}. */
  public TwoArraysImmutableHashMap<ParamName, Object> getAttrsIncludingSpecial() {
    TwoArraysImmutableHashMap.Builder<ParamName, Object> builder =
        TwoArraysImmutableHashMap.builder();
    for (Map.Entry<ParamName, Object> attr : getAttrs().entrySet()) {
      builder.put(attr.getKey(), attr.getValue());
    }
    if (!getVisibility().isEmpty()) {
      builder.put(VisibilityAttributes.VISIBILITY, getVisibility());
    }
    if (!getWithinView().isEmpty()) {
      builder.put(VisibilityAttributes.WITHIN_VIEW, getWithinView());
    }
    return builder.build();
  }

  @Nullable
  public Object get(ParamName name) {
    return getAttrs().get(name);
  }

  @Nullable
  public Object getBySnakeCase(String name) {
    return get(ParamName.bySnakeCase(name));
  }

  public static RawTargetNode of(
      ForwardRelativePath basePath,
      String buckType,
      ImmutableList<String> visibility,
      ImmutableList<String> withinView,
      TwoArraysImmutableHashMap<ParamName, Object> attrs) {
    Preconditions.checkArgument(!buckType.isEmpty());
    return ImmutableRawTargetNode.ofImpl(basePath, buckType, visibility, withinView, attrs);
  }

  public static RawTargetNode copyOf(
      ForwardRelativePath basePath,
      String buckType,
      ImmutableList<String> visibility,
      ImmutableList<String> withinView,
      Map<String, Object> attrs) {
    return of(
        basePath,
        buckType,
        visibility,
        withinView,
        attrs.entrySet().stream()
            .collect(
                TwoArraysImmutableHashMap.toMap(
                    e -> ParamName.bySnakeCase(e.getKey()), Map.Entry::getValue)));
  }
}
