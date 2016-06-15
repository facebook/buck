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

package com.facebook.buck.eden;

import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.eden.EdenError;
import com.facebook.eden.EdenService;
import com.facebook.thrift.TException;
import com.google.common.annotations.VisibleForTesting;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Eden thrift API for an individual mount.
 */
public final class EdenMount {
  private final EdenService.Client client;

  // Store this as a String because that is how it will be passed as the first argument to all of
  // the methods of EdenService.Client.
  private final String mountPoint;

  EdenMount(EdenService.Client client, Path mountPoint) {
    this.client = client;
    this.mountPoint = mountPoint.toString();
  }

  @VisibleForTesting
  Path getMountPoint() {
    return Paths.get(mountPoint);
  }

  public Sha1HashCode getSha1(Path entry) throws EdenError, TException {
    byte[] bytes = client.getSHA1(mountPoint, entry.toString());
    return Sha1HashCode.fromBytes(bytes);
  }

  @Override
  public String toString() {
    return String.format("EdenMount{mountPoint=%s}", mountPoint);
  }
}
