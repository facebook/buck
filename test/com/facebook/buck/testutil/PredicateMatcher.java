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

package com.facebook.buck.testutil;

import java.util.function.Predicate;
import org.hamcrest.CustomMatcher;

public final class PredicateMatcher<T> extends CustomMatcher<T> {

  private final Predicate<T> predicate;

  public PredicateMatcher(String description, Predicate<T> predicate) {
    super(description);
    this.predicate = predicate;
  }

  @SuppressWarnings("unchecked")
  @Override
  public boolean matches(Object o) {
    return predicate.test((T) o);
  }
}
