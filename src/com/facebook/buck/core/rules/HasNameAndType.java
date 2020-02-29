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

package com.facebook.buck.core.rules;

import com.facebook.buck.log.views.JsonViews;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;

/**
 * The type of rule descriptions that have both a name and a type, for use in eventing and logging.
 */
public interface HasNameAndType {
  @JsonProperty("name")
  @JsonView(JsonViews.MachineReadableLog.class)
  String getFullyQualifiedName();

  @JsonProperty("type")
  @JsonView(JsonViews.MachineReadableLog.class)
  String getType();
}
