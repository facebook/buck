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

package com.facebook.buck.core.test.rule;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.base.Preconditions;
import java.util.Map;
import org.immutables.value.Value;

/**
 * Freeform JSON to be used for the test protocol. The JSON is composed of {@link
 * com.facebook.buck.rules.macros.StringWithMacros}.
 *
 * <p>The JSON map keys must be {@link StringWithMacros}, and not other complicated collection
 * structures.
 */
@BuckStyleValue
public abstract class TestRunnerSpec {

  public abstract Object getData();

  @Value.Check
  protected void check() {
    // the json should be a map, iterable, a single stringwithmacros, a Number, or a Boolean
    Object object = getData();
    Preconditions.checkState(
        object instanceof Map
            || object instanceof Iterable
            || object instanceof StringWithMacros
            || object instanceof Number
            || object instanceof Boolean);
  }

  public static TestRunnerSpec of(Object data) {
    return ImmutableTestRunnerSpec.of(data);
  }
}
