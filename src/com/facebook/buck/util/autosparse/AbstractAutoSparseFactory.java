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

package com.facebook.buck.util.autosparse;

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.versioncontrol.HgCmdLineInterface;
import com.google.common.annotations.VisibleForTesting;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

public class AbstractAutoSparseFactory {
  private static final Logger LOG = Logger.get(AbstractAutoSparseFactory.class);

  @VisibleForTesting
  static Map<Path, WeakReference<AutoSparseState>> perSCRoot =
      new HashMap<Path, WeakReference<AutoSparseState>>();

  private AbstractAutoSparseFactory() {
    // Not called, utility class
  }

  @Nullable
  public static synchronized AutoSparseState getAutoSparseState(
      Path projectPath, HgCmdLineInterface hgCmdLine, AutoSparseConfig autoSparseConfig)
      throws InterruptedException {
    Path hgRoot = hgCmdLine.getHgRoot();
    if (hgRoot == null) {
      LOG.info("Failed to determine a mercurial root for %s", projectPath);
      return null;
    }

    if (perSCRoot.containsKey(hgRoot)) {
      AutoSparseState entry = perSCRoot.get(hgRoot).get();
      if (entry != null) {
        return entry;
      } else {
        perSCRoot.remove(hgRoot);
      }
    }

    HgAutoSparseState newState = new HgAutoSparseState(hgCmdLine, hgRoot, autoSparseConfig);
    perSCRoot.put(hgRoot, new WeakReference<>(newState));
    return newState;
  }
}
