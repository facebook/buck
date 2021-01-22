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

package com.facebook.buck.intellij.ideabuck.completion;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.ExceptionUtil;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** A project service that caches all the Buck alias per IDE session */
public class BuckAliasesCache {
  private static final Logger LOGGER = Logger.getInstance(BuckAliasesCache.class);
  private static final Gson GSON = new Gson();
  private static final String ALIAS_PREFIX = "alias.";
  private static final int NOT_READY = 0;
  private static final int IN_PROGRESS = 1;
  private static final int DONE = 2;
  private static final int TIMEOUT_MILLIS = 30000; // 30 seconds is long enough to get all aliases

  private final Project project;
  private final Map<String, String> aliasToTarget = new ConcurrentHashMap<>();
  private final AtomicInteger state = new AtomicInteger(0);

  public static BuckAliasesCache getInstance(Project project) {
    return ServiceManager.getService(project, BuckAliasesCache.class);
  }

  public BuckAliasesCache(Project project) {
    this.project = project;
  }

  /**
   * Returns a map from an alias to its buck target. May not return valid data until the background
   * buck audit command finishes
   */
  public Map<String, String> getAliasToTarget() {
    if (state.get() == DONE) {
      return aliasToTarget;
    }
    if (state.compareAndSet(NOT_READY, IN_PROGRESS)) {
      ApplicationManager.getApplication().executeOnPooledThread(this::parseAliases);
    }
    return Collections.emptyMap();
  }

  private void parseAliases() {
    aliasToTarget.clear();
    GeneralCommandLine commandLine = new GeneralCommandLine();
    commandLine.setExePath("buck");
    commandLine.addParameters("audit", "config", "alias", "--json");
    commandLine.setWorkDirectory(project.getBasePath());
    try {
      CapturingProcessHandler handler = new CapturingProcessHandler(commandLine);
      ProcessOutput output = handler.runProcess(TIMEOUT_MILLIS);
      Map<String, String> result =
          GSON.fromJson(output.getStdout(), new TypeToken<Map<String, String>>() {}.getType());
      for (Map.Entry<String, String> entry : result.entrySet()) {
        String key = entry.getKey();
        if (key.startsWith(ALIAS_PREFIX)) {
          key = key.substring(ALIAS_PREFIX.length());
        }
        aliasToTarget.put(key, entry.getValue());
      }
      state.set(DONE);
    } catch (ExecutionException e) {
      LOGGER.info(
          "Encountered exception while trying to run "
              + commandLine.getCommandLineString()
              + ": "
              + ExceptionUtil.getThrowableText(e));
      state.set(NOT_READY);
    }
  }
}
