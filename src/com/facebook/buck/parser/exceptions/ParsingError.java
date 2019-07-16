/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.parser.exceptions;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

/** Holds information about error occurred during parsing a build file */
@Value.Immutable(builder = false, copy = false)
@JsonDeserialize
public abstract class ParsingError {
  /** @return Human readable message of the error. */
  @Value.Parameter
  @JsonProperty("message")
  public abstract String getMessage();

  /** @return Parser stack trace to the errorred callsite, if any. */
  @Value.Parameter
  @JsonProperty("stacktrace")
  public abstract ImmutableList<String> getStackTrace();
}
