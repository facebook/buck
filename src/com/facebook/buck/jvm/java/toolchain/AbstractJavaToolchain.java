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

package com.facebook.buck.jvm.java.toolchain;

import com.facebook.buck.core.toolchain.Toolchain;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.jvm.java.JavacProvider;
import org.immutables.value.Value;

/** The java toolchain. */
@Value.Immutable
@BuckStyleImmutable
interface AbstractJavaToolchain extends Toolchain {
  String DEFAULT_NAME = "java-toolchain";

  @Override
  default String getName() {
    return DEFAULT_NAME;
  }

  JavacProvider getJavacProvider();
}
