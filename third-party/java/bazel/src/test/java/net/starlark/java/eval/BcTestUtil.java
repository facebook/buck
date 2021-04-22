package net.starlark.java.eval;

import com.google.common.collect.ImmutableList;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;

public class BcTestUtil {

  static Object eval(String program) throws Exception {
    return Starlark.execFile(
        ParserInput.fromString(program, "f.star"),
        FileOptions.DEFAULT, Module.create(),
        new StarlarkThread(Mutability.create(), StarlarkSemantics.DEFAULT));
  }

  static StarlarkFunction makeFunction(String program) throws Exception {
    return (StarlarkFunction) eval(program);
  }

  static ImmutableList<BcInstr.Opcode> opcodes(String makeFunction) throws Exception {
    StarlarkFunction function = makeFunction(makeFunction);
    return function.compiled.instructions().stream()
        .map(i -> i.opcode)
        .collect(ImmutableList.toImmutableList());
  }
}
