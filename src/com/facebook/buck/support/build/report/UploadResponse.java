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
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Optional;

/** The response sent by the server after uploading a {@link BuildReportUpload} if it succeeds. */
@BuckStyleValue
@JsonDeserialize(as = ImmutableUploadResponse.class)
abstract class UploadResponse {

  /** The uploaded file uri in case the server generated any */
  public abstract Optional<String> uri();
}
