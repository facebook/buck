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
        FileOptions.DEFAULT, module,
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
    Object result = Starlark.execFile(
        ParserInput.fromString(program, "f.star"),
        FileOptions.DEFAULT,
        module,
        thread);
    thread.mutability().freeze();
    return result;
  }

  static StarlarkFunction makeFunction(String program) throws Exception {
    return (StarlarkFunction) eval(program);
  }

  static StarlarkFunction makeFrozenFunction(String program) throws Exception {
    return makeFrozenFunction(program, ImmutableMap.of());
  }

  static StarlarkFunction makeFrozenFunction(String program, ImmutableMap<String, Object> globals) throws Exception {
    return (StarlarkFunction) evalAndFreeze(program, globals);
  }

  static ImmutableList<BcInstrOpcode> opcodes(String makeFunction) throws Exception {
    StarlarkFunction function = makeFunction(makeFunction);
    return function.compiled.instructions().stream()
        .map(i -> i.opcode)
        .collect(ImmutableList.toImmutableList());
  }
}
