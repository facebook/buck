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

package com.facebook.buck.doctor.config;

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.util.versioncontrol.VersionControlSupplier;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.ImmutableSet;
import java.io.InputStream;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
interface AbstractSourceControlInfo {
  /* Commit hash of the current revision. */
  String getCurrentRevisionId();
  /* A list of bookmarks that the current commit is based and also exist in TRACKED_BOOKMARKS */
  ImmutableSet<String> getBasedOffWhichTracked();
  /* Commit hash of the revision that is the common base between current revision and master. */
  Optional<String> getRevisionIdOffTracked();
  /* The timestamp of the base revision */
  Optional<Long> getRevisionTimestampOffTracked();
  /* The diff between base and current revision if it exists */
  @JsonIgnore
  Optional<VersionControlSupplier<InputStream>> getDiff();
  /* A list of all the files that are changed from the base revision. */
  ImmutableSet<String> getDirtyFiles();
}
