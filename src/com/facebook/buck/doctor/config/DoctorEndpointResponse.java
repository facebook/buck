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
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

@BuckStyleValue
@JsonDeserialize(as = ImmutableDoctorEndpointResponse.class)
public interface DoctorEndpointResponse {

  Optional<String> getErrorMessage();

  ImmutableList<DoctorSuggestion> getSuggestions();

  static DoctorEndpointResponse of(
      Optional<String> errorMessage, ImmutableList<DoctorSuggestion> suggestions) {
    return ImmutableDoctorEndpointResponse.of(errorMessage, suggestions);
  }
}
