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

  private final ImmutableList<String> headerNames;

  public UmbrellaHeader(ImmutableList<String> headerNames) {
    this.headerNames = headerNames;
  }

  public String render() {
    return headerNames.stream().map(x -> "#import \"" + x + "\"\n").reduce("", String::concat);
  }
}
