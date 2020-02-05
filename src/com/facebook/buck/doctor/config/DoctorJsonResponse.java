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

package com.facebook.buck.doctor.config;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.Iterables;
import java.util.Arrays;
import java.util.Optional;

@BuckStyleValue
@JsonDeserialize(as = ImmutableDoctorJsonResponse.class)
public interface DoctorJsonResponse {

  /** @return if the request and processing was successful from the server side. */
  boolean getRequestSuccessful();

  /** @return get the error message if it exists. */
  Optional<String> getErrorMessage();

  /** @return if the server wants to redirect or point to a remote url it will be here. */
  @JsonDeserialize
  Optional<String> getRageUrl();

  /** @return the message which is Json in the format/content that the server uses. */
  Optional<String> getMessage();

  /**
   * @return the id of the generated report. This is a workaround of the fact that the server does
   *     not respond with a dedicated field for the report id, and changing it it's too much
   *     trouble, so this pretends that it was in fact included in the response's json
   */
  @JsonIgnore
  default Optional<String> getReportId() {
    return getRageUrl().map(url -> Iterables.getLast(Arrays.asList(url.split("/"))));
  }

  static DoctorJsonResponse of(
      boolean requestSuccessful,
      Optional<String> errorMessage,
      Optional<String> rageUrl,
      Optional<String> message) {
    return ImmutableDoctorJsonResponse.of(requestSuccessful, errorMessage, rageUrl, message);
  }
}
