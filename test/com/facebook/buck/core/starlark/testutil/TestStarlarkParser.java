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

package com.facebook.buck.core.starlark.testutil;

import com.facebook.buck.core.starlark.compatible.TestMutableEnv;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.syntax.CallExpression;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.Expression;
import com.google.devtools.build.lib.syntax.ParserInput;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.syntax.SyntaxError;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Map;

public class TestStarlarkParser {

  public static CallExpression parseFuncall(String expr) {
    try {
      return (CallExpression)
          Expression.parse(ParserInput.create(expr, PathFragment.EMPTY_FRAGMENT));
    } catch (SyntaxError syntaxError) {
      throw new RuntimeException(syntaxError);
    }
  }

  public static Object eval(StarlarkThread env, String expr)
      throws EvalException, InterruptedException, SyntaxError {
    return EvalUtils.execAndEvalOptionalFinalExpression(
        ParserInput.create(expr, PathFragment.EMPTY_FRAGMENT), env);
  }

  public static Object eval(String expr, Map<String, Object> globals)
      throws EvalException, InterruptedException, SyntaxError {
    try (TestMutableEnv env = new TestMutableEnv(ImmutableMap.copyOf(globals))) {
      return TestStarlarkParser.eval(env.getEnv(), expr);
    }
  }
}
