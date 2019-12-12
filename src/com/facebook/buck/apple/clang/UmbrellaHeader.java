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

package com.facebook.buck.apple.clang;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

public class UmbrellaHeader {

  private final String targetName;
  private final ImmutableList<String> headerNames;
  private final ImmutableSet<String> headersToExclude;

  public UmbrellaHeader(String targetName, ImmutableList<String> headerNames) {
    this.targetName = targetName;
    this.headerNames = headerNames;

    this.headersToExclude =
        ImmutableSet.of(
            // Target-Swift.h header file should be excluded from the list of header names.
            // The umbrella header should not import the generated Objective-C header.
            // It is the job of the module map to make sure that Swift code is accessible
            // to Objective-C via the Target-Swift.h header file.
            String.format("%s-Swift.h", targetName));
  }

  public String render() {
    return headerNames.stream()
        .filter(x -> !headersToExclude.contains(x))
        .map(x -> String.format("#import <%s/%s>\n", targetName, x))
        .reduce("", String::concat);
  }
}
