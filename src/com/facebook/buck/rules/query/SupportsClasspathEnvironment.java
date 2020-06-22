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

import com.facebook.buck.query.QueryEnvironment;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Required interface for any {@code QueryEnvironment} that wants to support {@code
 * ClasspathFunction}
 */
public interface SupportsClasspathEnvironment<T> extends QueryEnvironment<T> {
  /** Returns the classpath for all the targets in {@code targets} */
  Stream<T> getFirstOrderClasspath(Set<T> targets);

  /**
   * @return a filtered stream of targets where the rules they refer to are instances of the given
   *     clazz
   */
  Stream<T> restrictToInstancesOf(Set<T> targets, Class<?> clazz);
}
