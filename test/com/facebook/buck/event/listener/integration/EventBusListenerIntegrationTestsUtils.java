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

package com.facebook.buck.event.listener.integration;

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

class EventBusListenerIntegrationTestsUtils {

  private EventBusListenerIntegrationTestsUtils() {}

  static Path getLastBuildCommandLogDir(ProjectWorkspace workspace) throws IOException {
    Path logDir = workspace.getBuckPaths().getLogDir();
    List<Path> pathList =
        Files.list(workspace.resolve(logDir))
            .filter(Files::isDirectory)
            .filter(dir -> dir.getFileName().toString().contains("buildcommand"))
            .collect(Collectors.toList());
    return Iterables.getOnlyElement(pathList);
  }
}
