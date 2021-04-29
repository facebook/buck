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

package com.facebook.buck.skylark.parser.context;

import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.param.ParamName;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/** Rule representation internal to Starlark parser. */
@BuckStyleValue
public abstract class RecordedRule {
  public abstract ForwardRelPath getBasePath();

  public abstract String getBuckType();

  public abstract ImmutableList<String> getVisibility();

  public abstract ImmutableList<String> getWithinView();

  public abstract TwoArraysImmutableHashMap<ParamName, Object> getRawRule();

  public static RecordedRule of(
      ForwardRelPath basePath,
      String buckType,
      ImmutableList<String> visibility,
      ImmutableList<String> withinView,
      TwoArraysImmutableHashMap<ParamName, Object> args) {
    Preconditions.checkArgument(!buckType.isEmpty());
    return ImmutableRecordedRule.ofImpl(basePath, buckType, visibility, withinView, args);
  }
}
