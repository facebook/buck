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

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
@JsonDeserialize(as = DoctorSuggestion.class)
abstract class AbstractDoctorSuggestion {

  @Value.Parameter
  abstract StepStatus getStatus();

  @Value.Parameter
  abstract Optional<String> getArea();

  @Value.Parameter
  abstract String getSuggestion();

  public enum StepStatus {
    OK("\u2705", "OK"),
    WARNING("\u2757", "Warning"),
    ERROR("\u274C", "Error"),
    UNKNOWN("\u2753", "Unknown");

    private final String emoji;
    private final String text;

    StepStatus(String emoji, String text) {
      this.emoji = emoji;
      this.text = text;
    }

    public String getEmoji() {
      return this.emoji;
    }

    public String getText() {
      return this.text;
    }
  }
}
