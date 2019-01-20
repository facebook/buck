/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.manifestservice;

import com.facebook.buck.artifact_cache.thrift.Manifest;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.Closeable;

/** Async operations over a Manifest. */
public interface ManifestService extends Closeable {

  /** Appends one more entry to the manifest. Creates a new one if it does not already exist. */
  ListenableFuture<Void> appendToManifest(Manifest manifest);

  /** Fetch the current value of the Manifest. An empty list is returned if no value is present. */
  ListenableFuture<Manifest> fetchManifest(String manifestKey);

  /** Deletes an existing Manifest. */
  ListenableFuture<Void> deleteManifest(String manifestKey);

  /** Sets the Manifest for key. Overwrites existing one if it already exists. */
  ListenableFuture<Void> setManifest(Manifest manifest);
}
