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
package com.facebook.buck.parser;

import static com.facebook.buck.util.concurrent.MoreFutures.propagateCauseIfInstanceOf;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

/**
 * Abstract node parsing pipeline. Allows implementors to define their own logic for creating nodes
 * of type T.
 *
 * @param <T> The type of node this pipeline will produce (raw nodes, target nodes, etc)
 */
public interface BuildFileParsePipeline<T> extends AutoCloseable {

  /**
   * Obtain all {@link TargetNode}s from a build file. This may block if the file is not cached.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildFile absolute path to the file to process.
   * @return all targets from the file
   * @throws BuildFileParseException for syntax errors.
   */
  default T getAllNodes(Cell cell, Path buildFile) throws BuildFileParseException {
    try {
      return getAllNodesJob(cell, buildFile).get();
    } catch (Exception e) {
      propagateCauseIfInstanceOf(e, BuildFileParseException.class);
      propagateCauseIfInstanceOf(e, ExecutionException.class);
      propagateCauseIfInstanceOf(e, UncheckedExecutionException.class);
      throw new RuntimeException(e);
    }
  }

  /**
   * Asynchronously obtain all {@link TargetNode}s from a build file. This will leverage previously
   * cached raw contents of the file (if present) but will always loop over the contents, so
   * repeated calls (with the same args) are not free.
   *
   * <p>returned future may throw {@link BuildFileParseException} and {@link
   * HumanReadableException}.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildFile absolute path to the file to process.
   * @return future.
   */
  ListenableFuture<T> getAllNodesJob(Cell cell, Path buildFile) throws BuildTargetException;
}
