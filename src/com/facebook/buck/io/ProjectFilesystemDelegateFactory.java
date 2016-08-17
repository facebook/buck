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
import com.facebook.eden.EdenError;
import com.facebook.thrift.TException;

import java.io.IOException;
import java.nio.file.Path;

import javax.annotation.Nullable;

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
    EdenClient client = createEdenClientOrSwallowException();

    EdenMount mount = null;
    if (client != null) {
      try {
        mount = client.getMountFor(root);
      } catch (TException | EdenError e) {
        // If Eden is running but root is not a mount point, Eden getMountFor() should just return
        // null rather than throw an error.
        LOG.error(e, "Failed to find Eden client for %s.", root);
      }
    }

    if (mount != null) {
      return new EdenProjectFilesystemDelegate(mount);
    } else {
      return new DefaultProjectFilesystemDelegate(root);
    }
  }

  @Nullable
  private static EdenClient createEdenClientOrSwallowException() {
    if (Platform.detect() == Platform.WINDOWS) {
      return null;
    }

    try {
      return EdenClient.newInstance();
    } catch (IOException | TException e) {
      // Nothing to do: it is very common that there is no EdenClient.
      return null;
    }
  }
}
