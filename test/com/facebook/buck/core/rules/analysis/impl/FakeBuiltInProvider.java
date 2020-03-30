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

package com.facebook.buck.core.rules.analysis.impl;

import com.facebook.buck.core.rules.providers.Provider;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;

public class FakeBuiltInProvider implements Provider<FakeInfo> {

  private final Key<FakeInfo> key;
  private final String name;

  public FakeBuiltInProvider(String name) {
    this.name = name;
    this.key = new Key<FakeInfo>() {};
  }

  @Override
  public Key<FakeInfo> getKey() {
    return key;
  }

  @Override
  public String toString() {
    return name;
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.format("%s()", name);
  }
}
