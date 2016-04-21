/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;

import org.immutables.value.Value;

import java.io.IOException;

/**
 * Tuple containing a {@link SourcePath} along with a way of retrieving dependencies for that
 * {@code SourcePath}.
 *
 * Users of precompiled headers need to be able to provide the dependencies in order to work
 * correctly with DependencyFileRuleKey, as files included via precompiled headers are not emitted
 * by the compiler in the dep file.
 *
 * @see com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey
 */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractPrecompiledHeaderReference {
  public abstract SourcePath getSourcePath();

  /**
   * Returns a {@code Supplier} that can be invoked to read the dependency file lines.
   *
   * The {@code Supplier} may throw an {@code IOException} wrapped in a {@code RuntimeException} if
   * the underlying read threw an exception.
   *
   * The results are only valid if the referenced rule has already been built.
   *
   * @return {@code Supplier} that can be invoked to read the dependency file lines.
   */
  public abstract Supplier<ImmutableList<String>> getDepFileLines();

  public static PrecompiledHeaderReference from(final CxxPrecompiledHeader rule) {
    return PrecompiledHeaderReference.of(
        new BuildTargetSourcePath(rule.getBuildTarget()),
        new Supplier<ImmutableList<String>>() {
          @Override
          public ImmutableList<String> get() {
            try {
              return rule.readDepFileLines();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        });
  }
}
