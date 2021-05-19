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

package net.starlark.java.eval;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;

public class BcTestUtil {

  static Object eval(String program, ImmutableMap<String, Object> globals) throws Exception {
    Module module = Module.create();
    for (Map.Entry<String, Object> entry : globals.entrySet()) {
      module.setGlobal(entry.getKey(), entry.getValue());
    }
    return Starlark.execFile(
        ParserInput.fromString(program, "f.star"),
        FileOptions.DEFAULT,
        module,
        new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT));
  }

  static Object eval(String program) throws Exception {
    return eval(program, ImmutableMap.of());
  }

  static Object evalAndFreeze(String program, ImmutableMap<String, Object> globals)
      throws Exception {
    StarlarkThread thread = new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT);
    Module module = Module.create();
    for (Map.Entry<String, Object> entry : globals.entrySet()) {
      module.setGlobal(entry.getKey(), entry.getValue());
    }
    Object result =
        Starlark.execFile(
            ParserInput.fromString(program, "f.star"), FileOptions.DEFAULT, module, thread);
    thread.mutability().freeze();
    return result;
  }

  static StarlarkFunction makeFunction(String program) throws Exception {
    return (StarlarkFunction) eval(program);
  }

  static StarlarkFunction makeFrozenFunction(String program) throws Exception {
    return makeFrozenFunction(program, ImmutableMap.of());
  }

  static StarlarkFunction makeFrozenFunction(String program, ImmutableMap<String, Object> globals)
      throws Exception {
    return (StarlarkFunction) evalAndFreeze(program, globals);
  }

  static ImmutableList<BcInstrOpcode> opcodes(String makeFunction) throws Exception {
    StarlarkFunction function = makeFunction(makeFunction);
    return function.compiled.instructions().stream()
        .map(i -> i.opcode)
        .collect(ImmutableList.toImmutableList());
  }
}
