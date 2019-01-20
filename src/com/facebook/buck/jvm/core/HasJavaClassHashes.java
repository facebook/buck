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

package com.facebook.buck.jvm.core;

import com.facebook.buck.core.rules.BuildRule;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;

/**
 * This should be considered deprecated. It comes from a time before the target/action graph split
 * and input-based rule keys. It will be a lot of work to eliminate (including getting rid of
 * SmartDexingStep), but we should do it at some point.
 */
public interface HasJavaClassHashes extends BuildRule {

  /**
   * @return a (possibly empty) map of names of {@code .class} files in the output of this rule to
   *     SHA-1 hashes of their contents.
   */
  ImmutableSortedMap<String, HashCode> getClassNamesToHashes();
}
