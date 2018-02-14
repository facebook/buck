/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Optional;
import org.immutables.value.Value;

/** Provides a results summary of a manifest-based cache store/update process. */
@Value.Immutable
@BuckStyleImmutable
interface AbstractManifestStoreResult {

  // Since some value combinations are mutually-exclusive (and would be more properly modeled by
  // tagged unions), do some consistency checks.
  @Value.Check
  default void check() {
    Preconditions.checkArgument(
        !getManifestLoadError().isPresent() || didCreateNewManifest(),
        "manifest load error should only be provided if a new manifest was created");
  }

  /** @return whether an existing manifest was updated, or a new one was stored. */
  boolean didCreateNewManifest();

  /** @return stats for the stored manifest. */
  ManifestStats getManifestStats();

  /** @return the error generated when trying to load an existing manifest. */
  Optional<String> getManifestLoadError();

  /** @return a future wrapping the asynchronous cache upload. */
  Optional<ListenableFuture<Void>> getStoreFuture();
}
