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

package com.facebook.buck.doctor.config;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
@JsonDeserialize(as = DoctorJsonResponse.class)
abstract class AbstractDoctorJsonResponse {

  /** @return if the request and processing was successful from the server side. */
  @Value.Parameter
  abstract boolean getRequestSuccessful();

  /** @return get the error message if it exists. */
  @Value.Parameter
  abstract Optional<String> getErrorMessage();

  /** @return if the server wants to redirect or point to a remote url it will be here. */
  @Value.Parameter
  @JsonDeserialize
  abstract Optional<String> getRageUrl();

  /** @return the message which is Json in the format/content that the server uses. */
  @Value.Parameter
  abstract Optional<String> getMessage();
}
