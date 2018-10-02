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
import static com.google.common.base.Throwables.throwIfInstanceOf;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.concurrent.ExecutionException;

/**
 * Abstract node parsing pipeline. Allows implementors to define their own logic for creating nodes
 * of type T.
 *
 * @param <T> The type of node this pipeline will produce (raw nodes, target nodes, etc)
 */
public interface BuildTargetParsePipeline<T> extends AutoCloseable {

  /**
   * Obtain a {@link TargetNode}. This may block if the node is not cached.
   *
   * @param cell the {@link Cell} that the {@link BuildTarget} belongs to.
   * @param buildTarget name of the node we're looking for. The build file path is derived from it.
   * @return the node
   * @throws BuildFileParseException for syntax errors in the build file.
   * @throws BuildTargetException if the buildTarget is malformed
   */
  default T getNode(Cell cell, BuildTarget buildTarget)
      throws BuildFileParseException, BuildTargetException {
    try {
      return getNodeJob(cell, buildTarget).get();
    } catch (Exception e) {
      if (e.getCause() != null) {
        throwIfInstanceOf(e.getCause(), BuildFileParseException.class);
        throwIfInstanceOf(e.getCause(), BuildTargetException.class);
      }
      throwIfInstanceOf(e, BuildFileParseException.class);
      throwIfInstanceOf(e, BuildTargetException.class);
      propagateCauseIfInstanceOf(e, ExecutionException.class);
      propagateCauseIfInstanceOf(e, UncheckedExecutionException.class);
      throw new RuntimeException(e);
    }
  }

  /**
   * Asynchronously get the {@link TargetNode}. This leverages the cache.
   *
   * @param cell the {@link Cell} that the build file belongs to.
   * @param buildTarget name of the node we're looking for. The build file path is derived from it.
   * @return future.
   * @throws BuildTargetException when the buildTarget is malformed.
   */
  ListenableFuture<T> getNodeJob(Cell cell, BuildTarget buildTarget) throws BuildTargetException;
}
