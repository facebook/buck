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

package com.facebook.buck.apple.xcode.xcodeproj;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
interface AbstractCopyFilePhaseDestinationSpec {

  @Value.Parameter
  PBXCopyFilesBuildPhase.Destination getDestination();

  Optional<String> getPath();

}

