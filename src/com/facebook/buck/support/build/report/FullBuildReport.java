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

package com.facebook.buck.support.build.report;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.versioncontrol.FullVersionControlStats;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Optional;

/**
 * FullBuildReport is an object serialized and uploaded to the server by {@link BuildReportUpload}
 * to capture diagnostics about a specific buck invocation. This replaces the old way of uploading
 * things with the rage command.
 */
@BuckStyleValue
@JsonDeserialize(as = ImmutableFullBuildReport.class)
public interface FullBuildReport {

  /** @return the contents of the message that endpoint returned. */
  @JsonProperty
  Config currentConfig();

  @JsonProperty
  Optional<FullVersionControlStats> versionControlStats();
}
