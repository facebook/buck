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

package com.facebook.buck.intellij.ideabuck.ws;

import com.facebook.buck.intellij.ideabuck.ws.buckevents.BuckEventsHandlerInterface;
import com.intellij.openapi.project.Project;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BuckClientManager {

  private static final Map<Project, BuckClient> clientsByProject = new ConcurrentHashMap<>();

  public static BuckClient getOrCreateClient(
      Project project, BuckEventsHandlerInterface buckEventHandler) {
    synchronized (clientsByProject) {
      BuckClient client = clientsByProject.get(project);
      if (client == null) {
        client = new BuckClient(buckEventHandler, project);
        clientsByProject.put(project, client);
      }
      return client;
    }
  }

  static void removeClient(Project project) {
    clientsByProject.remove(project);
  }

  private BuckClientManager() {}
}
