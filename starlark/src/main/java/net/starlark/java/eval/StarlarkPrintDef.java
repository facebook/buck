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
