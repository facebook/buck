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

package com.facebook.buck.core.graph.transformation.executor.factory;

import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutorWithLocalStack;
import com.facebook.buck.core.graph.transformation.executor.impl.JavaExecutorBackedDefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.ToposortBasedDepsAwareExecutor;

/**
 * A factory for {@link DepsAwareExecutor}s.
 *
 * <p>This factory uses {@link DepsAwareExecutorType} to determine which implementation of the
 * {@link DepsAwareExecutor}s to use.
 */
public class DepsAwareExecutorFactory {

  private DepsAwareExecutorFactory() {}

  /**
   * @param type the {@link DepsAwareExecutorType} used to indicate which specific implementation of
   *     {@link DepsAwareExecutor} to return
   * @param parallelism the number of threads in parallel
   * @param <U> the type supported by the {@link DepsAwareExecutor}
   * @return a {@link DepsAwareExecutor} of the given parallelism with the specific implementation
   *     based on {@link DepsAwareExecutorType}
   */
  public static <U> DepsAwareExecutor<U, ?> create(DepsAwareExecutorType type, int parallelism) {
    switch (type) {
      case DEFAULT:
        return DefaultDepsAwareExecutor.of(parallelism);
      case DEFAULT_WITH_LS:
        return DefaultDepsAwareExecutorWithLocalStack.of(parallelism);
      case JAVA_BASED:
        return JavaExecutorBackedDefaultDepsAwareExecutor.of(parallelism);
      case TOPOSORT_BASED:
        return ToposortBasedDepsAwareExecutor.of(parallelism);
      default:
        throw new IllegalArgumentException(
            String.format("Unknown DepsAwareExecutorType: %s", type));
    }
  }
}
