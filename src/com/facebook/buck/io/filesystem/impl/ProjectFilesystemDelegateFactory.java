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

package com.facebook.buck.io.filesystem.impl;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.eden.EdenClientPool;
import com.facebook.buck.eden.EdenMount;
import com.facebook.buck.eden.EdenProjectFilesystemDelegate;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemDelegate;
import com.facebook.buck.util.config.Config;
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
  public static ProjectFilesystemDelegate newInstance(Path root, Config config) {
    Optional<EdenClientPool> pool = EdenClientPool.tryToCreateEdenClientPool(root);

    if (pool.isPresent()) {
      Optional<EdenMount> mount = EdenMount.createEdenMountForProjectRoot(root, pool.get());
      if (mount.isPresent()) {
        LOG.debug("Created eden mount for %s: %s", root, mount.get());
        return new EdenProjectFilesystemDelegate(
            mount.get(),
            new DefaultProjectFilesystemDelegate(mount.get().getProjectRoot()),
            config);
      } else {
        LOG.error("Failed to find Eden client for %s.", root);
      }
    }

    // No Eden or Mercurial info available, use the default
    return new DefaultProjectFilesystemDelegate(root);
  }
}
