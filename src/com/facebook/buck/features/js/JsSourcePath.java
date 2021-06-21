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

package com.facebook.buck.features.js;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import java.util.Optional;
import java.util.function.Function;

/**
 * Represents a path to a source file used in a JavaScript build. Can optionally refer to an
 * individual file out of a source directory using an "inner path" component.
 */
@BuckStyleValue
abstract class JsSourcePath implements AddsToRuleKey {
  @AddToRuleKey
  public abstract SourcePath getPath();

  @AddToRuleKey
  public abstract Optional<String> getInnerPath();

  public static JsSourcePath of(Either<SourcePath, Pair<SourcePath, String>> pathOrCompoundPath) {
    SourcePath sourcePath = pathOrCompoundPath.transform(Function.identity(), Pair::getFirst);
    Optional<String> innerPath =
        pathOrCompoundPath.transform(
            (SourcePath path) -> Optional.empty(),
            (Pair<SourcePath, String> compoundPath) -> Optional.of(compoundPath.getSecond()));
    return ImmutableJsSourcePath.ofImpl(sourcePath, innerPath);
  }
}
