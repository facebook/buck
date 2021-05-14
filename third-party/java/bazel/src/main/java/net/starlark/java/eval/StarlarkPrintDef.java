package net.starlark.java.eval;

import java.util.regex.Pattern;
import javax.annotation.Nullable;

class StarlarkPrintDef {
  @Nullable private static final Pattern dumpFunctionsRegex;

  static {
    String dumpFunctions =
        StarlarkRuntimeStats.ENABLED ? System.getenv("STARLARK_PRINT_DEF") : null;
    if (dumpFunctions != null) {
      dumpFunctionsRegex = Pattern.compile(dumpFunctions);
    } else {
      dumpFunctionsRegex = null;
    }
  }

  static void dumpIfShould(BcCompiled compiled, BcIr ir) {
    if (dumpFunctionsRegex == null) {
      return;
    }

    if (!dumpFunctionsRegex.matcher(compiled.getName()).matches()) {
      return;
    }

    StringBuilder sb = new StringBuilder();
    sb.append("def " + compiled.getName() + ":\n");
    sb.append("IR:\n");
    for (BcIrInstr instr : ir.instructions) {
      sb.append("  ").append(instr).append("\n");
    }
    sb.append("BC:\n");
    for (String instr : compiled.toStringInstructions()) {
      sb.append("  ").append(instr).append("\n");
    }

    StarlarkRuntimeStats.addDef(sb.toString());
  }
}
