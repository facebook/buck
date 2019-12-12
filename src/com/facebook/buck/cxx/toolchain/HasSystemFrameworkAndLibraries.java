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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.collect.ImmutableSortedSet;
import org.immutables.value.Value;

/**
 * Constructor args which specify system framework and library fields.
 *
 * <p>E.g. fields with members in the form of {@code $SDKROOT/user/lib/libz.dylib} or {@code
 * $SDKROOT/System/Library/Frameworks/Foundation.framework}.
 */
public interface HasSystemFrameworkAndLibraries extends BuildRuleArg {
  @Value.NaturalOrder
  ImmutableSortedSet<FrameworkPath> getFrameworks();

  @Value.NaturalOrder
  ImmutableSortedSet<FrameworkPath> getLibraries();
}
