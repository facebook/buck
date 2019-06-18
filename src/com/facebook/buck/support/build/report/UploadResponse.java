/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.support.build.report;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/**
 * The response sent by the server after uploading a {@link BuildReportUpload} While the top level
 * fields are type safe, the data within {@link #getContent()} is arbitrary, and may be things like
 * JSON strings. This format must be kept in sync with the server's representation.
 */
@BuckStyleValue
@JsonDeserialize(as = ImmutableUploadResponse.class)
abstract class UploadResponse {

  /** Human readable reason for the request's failure. e.g. no build id was included */
  public abstract Optional<String> getErrorMessage();

  /** A map with arbitrary content if the action is successful */
  public abstract Optional<ImmutableMap<String, String>> getContent();

  /** Whether the action was completed correctly by the server or not. */
  public abstract Optional<Boolean> getSuccess();
}
