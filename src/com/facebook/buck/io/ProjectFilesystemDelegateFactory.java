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

import com.facebook.buck.eden.EdenClient;
import com.facebook.buck.eden.EdenMount;
import com.facebook.buck.eden.EdenProjectFilesystemDelegate;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.environment.Platform;
import com.facebook.eden.thrift.EdenError;
import com.facebook.thrift.TException;

import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link ProjectFilesystemDelegateFactory} mediates the creation of a
 * {@link ProjectFilesystemDelegate} for a {@link ProjectFilesystem} root.
 */
public final class ProjectFilesystemDelegateFactory {

  private static final Logger LOG = Logger.get(ProjectFilesystemDelegateFactory.class);

  /** Utility class: do not instantiate. */
  private ProjectFilesystemDelegateFactory() {}

  /**
   * Must always create a new delegate for the specified {@code root}.
   */
  public static ProjectFilesystemDelegate newInstance(Path root) {
    Optional<EdenClient> client = tryToCreateEdenClient();

    if (client.isPresent()) {
      try {
        EdenMount mount = client.get().getMountFor(root);
        if (mount != null) {
          return new EdenProjectFilesystemDelegate(mount);
        }
      } catch (TException | EdenError e) {
        // If Eden is running but root is not a mount point, Eden getMountFor() should just return
        // null rather than throw an error.
        LOG.error(e, "Failed to find Eden client for %s.", root);
      }
    }

    return new DefaultProjectFilesystemDelegate(root);
  }

  /** @return {@link Optional#empty()} if there is no instance of Eden running. */
  private static Optional<EdenClient> tryToCreateEdenClient() {
    if (Platform.detect() != Platform.WINDOWS) {
      return EdenClient.newInstance();
    } else {
      return Optional.empty();
    }
  }
}
