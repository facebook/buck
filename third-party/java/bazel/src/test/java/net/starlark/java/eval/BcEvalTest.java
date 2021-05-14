package net.starlark.java.eval;

import static org.junit.Assert.*;

import com.google.common.io.Files;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public class BcEvalTest {
  @Test
  public void caseOrder() throws Exception {
    String bcEvalJava = System.getenv("BC_EVAL_JAVA");
    if (bcEvalJava == null) {
      // running from Idea
      bcEvalJava = "src/main/java/" + BcEval.class.getName().replace(".", "/") + ".java";
    }
    Pattern pattern = Pattern.compile(" +case BcInstr.(.+):");

    BcInstr.Opcode expectedOpcode = BcInstr.Opcode.values()[0];
    for (String line : Files.readLines(new File(bcEvalJava), StandardCharsets.UTF_8)) {
      Matcher matcher = pattern.matcher(line);
      if (!matcher.matches()) {
        continue;
      }
      String opcodeName = matcher.group(1);
      assertNotNull(expectedOpcode);
      assertEquals(expectedOpcode.name(), opcodeName);
      if (expectedOpcode.ordinal() == BcInstr.Opcode.values().length - 1) {
        expectedOpcode = null;
      } else {
        expectedOpcode = BcInstr.Opcode.values()[expectedOpcode.ordinal() + 1];
      }
    }
    assertNull(expectedOpcode);
  }
}
