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

package com.facebook.buck.rules;

import com.facebook.buck.util.Threads;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.io.IOException;
import javax.annotation.Nonnull;
import org.immutables.value.Value;

/** Lazily constructs {@link KnownBuildRuleTypes} for {@link Cell}s. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractKnownBuildRuleTypesProvider {

  abstract KnownBuildRuleTypesFactory getKnownBuildRuleTypesFactory();

  @Value.Derived
  protected LoadingCache<Cell, KnownBuildRuleTypes> getKnownBuildRuleTypesCache() {
    return CacheBuilder.newBuilder()
        .weakKeys()
        .build(
            new CacheLoader<Cell, KnownBuildRuleTypes>() {
              @Override
              public KnownBuildRuleTypes load(@Nonnull Cell cell) throws Exception {
                try {
                  return getKnownBuildRuleTypesFactory().create(cell);
                } catch (IOException e) {
                  throw new RuntimeException(
                      String.format(
                          "Creation of KnownBuildRuleTypes failed for Cell rooted at [%s].",
                          cell.getFilesystem().getRootPath()),
                      e);
                } catch (InterruptedException e) {
                  Threads.interruptCurrentThread();
                  throw new RuntimeException(
                      String.format(
                          "Creation of KnownBuildRuleTypes failed for Cell rooted at [%s].",
                          cell.getFilesystem().getRootPath()),
                      e);
                }
              }
            });
  }

  public KnownBuildRuleTypes get(Cell cell) {
    try {
      return getKnownBuildRuleTypesCache().getUnchecked(cell);
    } catch (UncheckedExecutionException e) {
      Throwables.throwIfUnchecked(e.getCause());
      throw e;
    }
  }
}
