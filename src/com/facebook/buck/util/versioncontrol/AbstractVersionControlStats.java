/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.versioncontrol;

import com.facebook.buck.log.views.JsonViews;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
interface AbstractVersionControlStats {

  @JsonView(JsonViews.MachineReadableLog.class)
  ImmutableSet<String> getPathsChangedInWorkingDirectory();

  @JsonView(JsonViews.MachineReadableLog.class)
  String getCurrentRevisionId();

  @JsonView(JsonViews.MachineReadableLog.class)
  ImmutableSet<String> getBaseBookmarks();

  @JsonView(JsonViews.MachineReadableLog.class)
  String getBranchedFromMasterRevisionId();

  @JsonView(JsonViews.MachineReadableLog.class)
  long getBranchedFromMasterTsMillis();
}
