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

package com.facebook.buck.core.build.engine.manifest;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.base.Preconditions;
import java.util.Optional;
import org.immutables.value.Value;

/** A union of results of loading a {@link Manifest} from it's serialized form. */
@BuckStyleValue
public abstract class ManifestLoadResult {

  @Value.Check
  void check() {
    Preconditions.checkArgument(getManifest().isPresent() ^ getError().isPresent());
  }

  public abstract Optional<Manifest> getManifest();

  public abstract Optional<String> getError();

  public static ManifestLoadResult success(Manifest manifest) {
    return ImmutableManifestLoadResult.of(Optional.of(manifest), Optional.empty());
  }

  public static ManifestLoadResult error(String error) {
    return ImmutableManifestLoadResult.of(Optional.empty(), Optional.of(error));
  }
}
