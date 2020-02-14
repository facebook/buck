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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.event.SimplePerfEvent;
import java.util.function.Function;

/** Convert {@link UnconfiguredTargetNode} to {@link TargetNode} for the parser. */
public interface ParserTargetNodeFromUnconfiguredTargetNodeFactory {
  TargetNodeMaybeIncompatible createTargetNode(
      Cell cell,
      AbsPath buildFile,
      BuildTarget target,
      DependencyStack dependencyStack,
      UnconfiguredTargetNode rawNode,
      Function<SimplePerfEvent.PerfEventId, SimplePerfEvent.Scope> perfEventScope);
}
