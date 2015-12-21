/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TargetNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.Map;

public interface DaemonicParserState {
  ImmutableList<Map<String, Object>> getAllRawNodes(
      Cell cell,
      ProjectBuildFileParser parser,
      Path buildFile) throws BuildFileParseException, InterruptedException;

  ImmutableSet<TargetNode<?>> getAllTargetNodes(
      BuckEventBus eventBus,
      Cell cell,
      ProjectBuildFileParser parser,
      Path buildFile,
      TargetNodeListener nodeListener) throws BuildFileParseException, InterruptedException;

  TargetNode<?> getTargetNode(
      BuckEventBus eventBus,
      Cell cell,
      ProjectBuildFileParser parser,
      BuildTarget target,
      TargetNodeListener nodeListener) throws BuildFileParseException, InterruptedException;

  void invalidateBasedOn(WatchEvent<?> event) throws InterruptedException;

  void invalidatePath(Path path) throws InterruptedException;
}
