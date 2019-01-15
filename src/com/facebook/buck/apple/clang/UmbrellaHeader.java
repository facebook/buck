/*
 * Copyright 2014-present Facebook, Inc.
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
package com.facebook.buck.apple.clang;

import com.google.common.collect.ImmutableList;

public class UmbrellaHeader {

  private final String targetName;
  private final ImmutableList<String> headerNames;

  public UmbrellaHeader(String targetName, ImmutableList<String> headerNames) {
    this.targetName = targetName;
    this.headerNames = headerNames;
  }

  public String render() {
    String swiftGeneratedHeader = String.format("%s-Swift.h", targetName);
    return headerNames
        .stream()
        // Remove the Target-Swift.h header file from the list of header names.
        // The umbrella header should not import the generated Objective-C header.
        // It is the job of the module map to make sure that Swift code is accessible
        // to Objective-C via the Target-Swift.h header file.
        .filter(x -> !x.equals(swiftGeneratedHeader))
        .map(x -> String.format("#import <%s/%s>\n", targetName, x))
        .reduce("", String::concat);
  }
}
