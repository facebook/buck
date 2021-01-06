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

package com.facebook.buck.core.rules.knowntypes.provider;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.rules.knowntypes.HybridKnownRuleTypes;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypesFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.core.starlark.knowntypes.KnownUserDefinedRuleTypes;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.options.UserDefinedRulesState;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;

/** Lazily constructs {@link KnownRuleTypes} for {@link Cell}s. */
public class KnownRuleTypesProvider {

  private final LoadingCache<Cell, KnownNativeRuleTypes> nativeTypesCache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<Cell, KnownNativeRuleTypes>() {
                @Override
                public KnownNativeRuleTypes load(Cell cell) {
                  return knownNativeRuleTypesFactory.create(cell);
                }
              });

  private final LoadingCache<Cell, KnownUserDefinedRuleTypes> userDefinedTypesCache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<Cell, KnownUserDefinedRuleTypes>() {
                @Override
                public KnownUserDefinedRuleTypes load(Cell cell) {
                  return new KnownUserDefinedRuleTypes();
                }
              });

  private final LoadingCache<Cell, KnownRuleTypes> typesCache =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<Cell, KnownRuleTypes>() {
                @Override
                public KnownRuleTypes load(Cell cell) {
                  if (cell.getBuckConfig().getView(ParserConfig.class).getUserDefinedRulesState()
                      == UserDefinedRulesState.ENABLED) {
                    return new HybridKnownRuleTypes(
                        getNativeRuleTypes(cell), getUserDefinedRuleTypes(cell));
                  } else {
                    return getNativeRuleTypes(cell);
                  }
                }
              });

  private final KnownNativeRuleTypesFactory knownNativeRuleTypesFactory;

  public KnownRuleTypesProvider(KnownNativeRuleTypesFactory knownNativeRuleTypesFactory) {
    this.knownNativeRuleTypesFactory = knownNativeRuleTypesFactory;
  }

  /** Returns {@link KnownRuleTypes} for a given {@link Cell}. */
  public KnownRuleTypes get(Cell cell) {
    try {
      return typesCache.getUnchecked(cell);
    } catch (UncheckedExecutionException e) {
      Throwables.throwIfUnchecked(e.getCause());
      throw e;
    }
  }

  /** Get details for just rules implemented in Buck itself, rather than user defined rules */
  public KnownNativeRuleTypes getNativeRuleTypes(Cell cell) {
    try {
      return nativeTypesCache.getUnchecked(cell);
    } catch (UncheckedExecutionException e) {
      Throwables.throwIfUnchecked(e.getCause());
      throw e;
    }
  }

  /** Get details for rules defined by users in extension files */
  public KnownUserDefinedRuleTypes getUserDefinedRuleTypes(Cell cell) {
    try {
      return userDefinedTypesCache.getUnchecked(cell);
    } catch (UncheckedExecutionException e) {
      Throwables.throwIfUnchecked(e.getCause());
      throw e;
    }
  }
}
