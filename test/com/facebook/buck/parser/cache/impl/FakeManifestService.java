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

package com.facebook.buck.parser.cache.impl;

import com.facebook.buck.artifact_cache.thrift.Manifest;
import com.facebook.buck.manifestservice.ManifestService;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A fake manifest service to be used in tests without going over a wire. */
public class FakeManifestService implements ManifestService {
  private final Map<String, ArrayList<ByteBuffer>> fingerprints = new HashMap<>();

  /** Appends one more entry to the manifest. Creates a new one if it does not already exist. */
  @Override
  public ListenableFuture<Void> appendToManifest(Manifest manifest) {
    return addToManifestBackingCollection(manifest);
  }

  /** Fetch the current value of the Manifest. An empty list is returned if no value is present. */
  @Override
  public ListenableFuture<Manifest> fetchManifest(String manifestKey) {
    Manifest manifest = new Manifest();
    manifest.setKey(manifestKey);

    List<ByteBuffer> storedValues = fingerprints.get(manifestKey);
    if (storedValues == null) {
      storedValues = ImmutableList.of();
    }
    manifest.setValues(storedValues);
    return Futures.immediateFuture(manifest);
  }

  /** Deletes an existing Manifest. */
  @Override
  public ListenableFuture<Void> deleteManifest(String manifestKey) {
    fingerprints.remove(manifestKey);
    return Futures.immediateFuture(null);
  }

  /** Sets the Manifest for key. Overwrites existing one if it already exists. */
  @Override
  public ListenableFuture<Void> setManifest(Manifest manifest) {
    return addToManifestBackingCollection(manifest);
  }

  private ListenableFuture<Void> addToManifestBackingCollection(Manifest manifest) {
    String key = manifest.key;
    ArrayList<ByteBuffer> fingerprintsForKey = fingerprints.get(key);
    if (fingerprintsForKey == null) {
      fingerprintsForKey = new ArrayList<>();
      fingerprints.put(key, fingerprintsForKey);
    }

    for (ByteBuffer bytes : manifest.values) {
      fingerprintsForKey.add(bytes);
    }

    return Futures.immediateFuture(null);
  }

  @Override
  public void close() throws IOException {}
}
