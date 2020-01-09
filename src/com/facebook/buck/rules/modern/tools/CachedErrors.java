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

package com.facebook.buck.rules.modern.tools;

import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.util.types.Pair;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;

/** A node in the graph of found paths/errors for the IsolationChecker. */
@BuckStyleValueWithBuilder
interface CachedErrors {
  List<Pair<String, Path>> getPaths();

  List<Pair<String, Throwable>> getExceptions();

  List<Pair<String, CachedErrors>> getReferences();

  /** Convenience for iterating over both errors and paths. */
  default void forEach(
      BiConsumer<String, Path> pathHandler, BiConsumer<String, Throwable> errorConsumer) {
    forEachPath(pathHandler);
    forEachError(errorConsumer);
  }

  /** Iterates over all the found paths. */
  default void forEachPath(BiConsumer<String, Path> consumer) {
    getPaths().forEach(pair -> consumer.accept(pair.getFirst(), pair.getSecond()));
    getReferences()
        .forEach(
            refPair ->
                refPair
                    .getSecond()
                    .forEachPath(
                        (crumb, path) ->
                            consumer.accept(
                                String.format("%s%s", refPair.getFirst(), crumb), path)));
  }

  /** Iterates over all the found errors. */
  default void forEachError(BiConsumer<String, Throwable> consumer) {
    getExceptions().forEach(pair -> consumer.accept(pair.getFirst(), pair.getSecond()));
    getReferences()
        .forEach(
            refPair ->
                refPair
                    .getSecond()
                    .forEachError(
                        (crumb, error) ->
                            consumer.accept(
                                String.format("%s%s", refPair.getFirst(), crumb), error)));
  }
}
