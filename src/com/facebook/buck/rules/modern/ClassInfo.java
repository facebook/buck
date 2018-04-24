/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.modern;

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.google.common.collect.ImmutableCollection;
import java.util.Optional;

/**
 * ClassInfo is used by ModernBuildRule to extract information from an AddsToRuleKey instance. It
 * computes various things (rulekeys, deps, etc) by iterating over all the fields of the
 * AddsToRuleKey.
 */
public interface ClassInfo<T extends AddsToRuleKey> {
  /** Returns a lower underscore name for this type. */
  String getType();

  <E extends Exception> void visit(T value, ValueVisitor<E> visitor) throws E;

  Optional<ClassInfo<? super T>> getSuperInfo();

  ImmutableCollection<FieldInfo<?>> getFieldInfos();
}
