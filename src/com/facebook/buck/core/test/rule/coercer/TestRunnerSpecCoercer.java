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

package com.facebook.buck.core.test.rule.coercer;

import com.facebook.buck.core.test.rule.CoercedTestRunnerSpec;
import com.facebook.buck.core.test.rule.TestRunnerSpec;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

/**
 * Coerces a {@link TestRunnerSpec} to a {@link CoercedTestRunnerSpec} by resolving the {@link
 * StringWithMacros} to {@link Arg}s.
 */
public class TestRunnerSpecCoercer {

  private TestRunnerSpecCoercer() {}

  /**
   * Coerces the given freeform JSON with {@link StringWithMacros} in the {@link TestRunnerSpec} to
   * become a freeform JSON of {@link Arg}s, contained in {@link CoercedTestRunnerSpec}.
   */
  public static CoercedTestRunnerSpec coerce(
      TestRunnerSpec spec, StringWithMacrosConverter converter) {
    return spec.match(
        new TestRunnerSpec.Matcher<CoercedTestRunnerSpec>() {
          @Override
          public CoercedTestRunnerSpec map(ImmutableMap<StringWithMacros, TestRunnerSpec> map) {
            ImmutableMap.Builder<Arg, CoercedTestRunnerSpec> mapBuilder = ImmutableMap.builder();
            for (Map.Entry<StringWithMacros, TestRunnerSpec> entry : map.entrySet()) {
              mapBuilder.put(
                  converter.convert(entry.getKey()), coerce(entry.getValue(), converter));
            }
            return CoercedTestRunnerSpec.of(mapBuilder.build());
          }

          @Override
          public CoercedTestRunnerSpec list(ImmutableList<TestRunnerSpec> iterable) {
            ImmutableList.Builder<CoercedTestRunnerSpec> list = ImmutableList.builder();
            for (TestRunnerSpec item : iterable) {
              list.add(coerce(item, converter));
            }
            return CoercedTestRunnerSpec.of(list.build());
          }

          @Override
          public CoercedTestRunnerSpec stringWithMacros(StringWithMacros stringWithMacros) {
            return CoercedTestRunnerSpec.of(converter.convert(stringWithMacros));
          }

          @Override
          public CoercedTestRunnerSpec number(Number number) {
            return CoercedTestRunnerSpec.of(number);
          }

          @Override
          public CoercedTestRunnerSpec bool(boolean b) {
            return CoercedTestRunnerSpec.of(b);
          }
        });
  }
}
