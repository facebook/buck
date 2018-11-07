/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.build.strategy.BuildRuleStrategy;
import com.facebook.buck.remoteexecution.Protocol;
import com.facebook.buck.remoteexecution.util.FileTreeBuilder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.util.function.ThrowingFunction;
import com.google.common.hash.HashCode;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Interface used to implement various isolated execution strategies. In these strategies,
 * buildrules are run in isolation (i.e. outside of the real build root, in a different directory,
 * on a different machine, etc).
 */
public interface IsolatedExecution extends Closeable {
  void build(
      ExecutionContext executionContext,
      FileTreeBuilder inputsBuilder,
      Set<Path> outputs,
      Path projectRoot,
      HashCode hash,
      BuildTarget buildTarget,
      Path cellPrefixRoot)
      throws IOException, StepFailedException, InterruptedException;

  Protocol getProtocol();

  /** Creates a BuildRuleStrategy for a particular */
  static BuildRuleStrategy createIsolatedExecutionStrategy(
      IsolatedExecution executionStrategy,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellResolver,
      Cell rootCell,
      ThrowingFunction<Path, HashCode, IOException> fileHasher,
      Optional<ExecutorService> executorService) {
    return new IsolatedExecutionStrategy(
        executionStrategy, ruleFinder, cellResolver, rootCell, fileHasher, executorService);
  }
}
