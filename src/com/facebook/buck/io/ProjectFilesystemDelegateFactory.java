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

package com.facebook.buck.io;

import com.facebook.buck.config.Config;
import com.facebook.buck.eden.EdenClientPool;
import com.facebook.buck.eden.EdenMount;
import com.facebook.buck.eden.EdenProjectFilesystemDelegate;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.PrintStreamProcessExecutorFactory;
import com.facebook.buck.util.autosparse.AbstractAutoSparseConfig;
import com.facebook.buck.util.autosparse.AbstractAutoSparseFactory;
import com.facebook.buck.util.autosparse.AutoSparseConfig;
import com.facebook.buck.util.autosparse.AutoSparseProjectFilesystemDelegate;
import com.facebook.buck.util.autosparse.AutoSparseState;
import com.facebook.buck.util.versioncontrol.HgCmdLineInterface;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link ProjectFilesystemDelegateFactory} mediates the creation of a {@link
 * ProjectFilesystemDelegate} for a {@link ProjectFilesystem} root.
 */
public final class ProjectFilesystemDelegateFactory {

  private static final Logger LOG = Logger.get(ProjectFilesystemDelegateFactory.class);

  /** Utility class: do not instantiate. */
  private ProjectFilesystemDelegateFactory() {}

  /** Must always create a new delegate for the specified {@code root}. */
  public static ProjectFilesystemDelegate newInstance(
      Path root, Path buckOut, String hgCmd, Config config) throws InterruptedException {
    Optional<EdenClientPool> pool = EdenClientPool.tryToCreateEdenClientPool(root);

    if (pool.isPresent()) {
      Optional<EdenMount> mount = EdenMount.createEdenMountForProjectRoot(root, pool.get());
      if (mount.isPresent()) {
        LOG.debug("Created eden mount for %s: %s", root, mount.get());
        return new EdenProjectFilesystemDelegate(mount.get(), config);
      } else {
        LOG.error("Failed to find Eden client for %s.", root);
      }
    }

    if (AbstractAutoSparseConfig.isAutosparseEnabled(config)) {
      AutoSparseConfig autoSparseConfig = AutoSparseConfig.of(config);
      // Grab a copy of the current environment; Mercurial sometimes cares (or more specifically,
      // a remote connection command like ssh cares). We rather not pass in an environment via the
      // ProjectFilesystem here because that'd make the ProjectFilesystem variant on the env, not
      // a can of worms you want to go open just to make Mercurial happy.
      ImmutableMap<String, String> environment = ImmutableMap.copyOf(System.getenv());
      HgCmdLineInterface hgCmdLine =
          new HgCmdLineInterface(new PrintStreamProcessExecutorFactory(), root, hgCmd, environment);
      AutoSparseState autoSparseState =
          AbstractAutoSparseFactory.getAutoSparseState(root, buckOut, hgCmdLine, autoSparseConfig);
      if (autoSparseState != null) {
        LOG.debug("Autosparse enabled, using AutoSparseProjectFilesystemDelegate");
        return new AutoSparseProjectFilesystemDelegate(autoSparseState, root);
      }
    }

    // No Eden or Mercurial info available, use the default
    return new DefaultProjectFilesystemDelegate(root);
  }
}
