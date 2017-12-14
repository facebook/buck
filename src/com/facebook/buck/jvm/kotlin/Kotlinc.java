/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

/** Interface for a kotlin compiler. */
public interface Kotlinc extends Tool {

  KotlincVersion getVersion();

  int buildWithClasspath(
      ExecutionContext context,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableSortedSet<Path> kotlinSourceFilePaths,
      Path pathToSrcsList,
      Optional<Path> workingDirectory,
      ProjectFilesystem fileSystem)
      throws InterruptedException;

  String getDescription(
      ImmutableList<String> options,
      ImmutableSortedSet<Path> kotlinSourceFilePaths,
      Path pathToSrcsList);

  String getShortName();
}
