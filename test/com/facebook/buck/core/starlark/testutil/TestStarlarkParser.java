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
import java.util.Map;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.Expression;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.SyntaxError;

public class TestStarlarkParser {

  public static CallExpression parseFuncall(String expr) {
    try {
      return (CallExpression) Expression.parse(ParserInput.fromString(expr, "noname.bzl"));
    } catch (SyntaxError.Exception syntaxError) {
      throw new RuntimeException(syntaxError);
    }
  }

  public static Object eval(StarlarkThread env, Module module, String expr)
      throws EvalException, InterruptedException, SyntaxError.Exception {
    return Starlark.execFile(
        ParserInput.fromString(expr, "eval.bzl"), FileOptions.DEFAULT, module, env);
  }

  public static Object eval(String expr, Map<String, Object> globals) throws Exception {
    try (TestMutableEnv env = new TestMutableEnv(ImmutableMap.copyOf(globals))) {
      return TestStarlarkParser.eval(env.getEnv(), env.getModule(), expr);
    }
  }
}
