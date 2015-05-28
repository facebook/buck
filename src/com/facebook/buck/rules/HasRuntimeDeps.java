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

package com.facebook.buck.rules;

import com.google.common.collect.ImmutableSortedSet;

/**
 * Provides a facility for a rule to list dependencies it'll need at runtime.
 * <p>
 * Consider the case of a Java test.  The {@link com.facebook.buck.java.JavaTest} rule itself is
 * just a {@link com.facebook.buck.java.DefaultJavaLibrary} and so only lists it's immediate compile
 * time deps as it's normal first-order dependencies.  However, to actually run a Java test, we need
 * it's entire transitive dependency tree locally on disk.  Since this is outside the contract of
 * normal build dependencies (e.g. a top-down build engine may decide not to pull a dependency
 * locally if the top-level target can be pull form cache, and therefore won't need to be built),
 * we need some other way to convey to the build engine that a set of rules that need to be on disk
 * by the end of the build.
 * <p>
 * Enter this interface.  While it serves an important purpose, it's long-term semantics aren't
 * entirely clear yet, so expect how we model this and how the build engine uses this to change in
 * the future.
 */
public interface HasRuntimeDeps {
  ImmutableSortedSet<BuildRule> getRuntimeDeps();
}
