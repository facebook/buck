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

package net.starlark.java.annot.processor;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.Writer;
import java.util.Comparator;
import java.util.Set;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import net.starlark.java.annot.internal.BcOpcodeHandler;
import net.starlark.java.annot.internal.BcOpcodeNumber;

/** Generate switch by opcode. */
class BcEvalDispatchGen {

  private final Filer filer;
  private final Messager messager;

  BcEvalDispatchGen(ProcessingEnvironment env) {
    filer = env.getFiler();
    messager = env.getMessager();
  }

  void gen(RoundEnvironment roundEnv) {
    Set<? extends Element> bcEvalHandlers =
        roundEnv.getElementsAnnotatedWith(BcOpcodeHandler.class);

    if (bcEvalHandlers.isEmpty()) {
      return;
    }

    try {
      genBcEvalDispatch(bcEvalHandlers);
    } catch (Exception e) {
      messager.printMessage(Diagnostic.Kind.ERROR, e.toString());
    }
  }

  private static class Handler {
    private final Element method;
    private final BcOpcodeHandler annotation;

    Handler(Element method, BcOpcodeHandler annotation) {
      this.method = method;
      this.annotation = annotation;
    }
  }

  private Handler handlerInfo(Element element) {
    BcOpcodeHandler annotation = element.getAnnotation(BcOpcodeHandler.class);
    if (annotation == null) {
      throw new IllegalStateException();
    }
    return new Handler(element, annotation);
  }

  private void genBcEvalDispatch(Set<? extends Element> bcEvalHandlers) throws IOException {
    ImmutableList<Handler> handlers =
        bcEvalHandlers.stream()
            .map(this::handlerInfo)
            .sorted(Comparator.comparing(h -> h.annotation.opcode()))
            .collect(ImmutableList.toImmutableList());

    JavaFileObject classFile =
        filer.createSourceFile(
            "net.starlark.java.eval.BcEvalDispatch", bcEvalHandlers.toArray(new Element[0]));
    Writer writer = classFile.openWriter();
    SourceWriter sw = new SourceWriter(writer);
    sw.writeLineF("package net.starlark.java.eval;");
    sw.writeLine("");
    sw.writeLineF("// @javax.annotation.Generated(\"%s\")", this.getClass().getName());
    sw.writeLine("@java.lang.SuppressWarnings({\"all\"})");
    sw.writeLine("class BcEvalDispatch {");
    sw.indented(
        () -> {
          genRun(handlers, sw);
          sw.writeLine("");
          genUnimplementedOpcodesArray(handlers, sw);
        });
    sw.writeLine("}");
    writer.flush();
    writer.close();
  }

  private void genRun(ImmutableList<Handler> handlers, SourceWriter sw) throws IOException {
    sw.writeLine("// Run the eval loop");
    sw.writeLine("static Object run(BcEval eval)");
    sw.writeLine("     throws EvalException, InterruptedException {");
    sw.indented(
        () -> {
          sw.whileBlock(
              "eval.ip != eval.text.length",
              () -> {
                sw.ifBlock(
                    "++eval.thread.steps >= eval.thread.stepLimit",
                    () -> {
                      sw.writeLine(
                          "throw new EvalException(\""
                              + "Starlark computation cancelled: too many steps\");");
                    });
                sw.writeLine("// store the IP of the instruction");
                sw.writeLine("// to be able to obtain instruction location");
                sw.writeLine("eval.currentIp = eval.ip;");
                sw.writeLine("int opcode = eval.text[eval.ip++];");
                sw.ifBlock(
                    "StarlarkRuntimeStats.ENABLED",
                    () -> {
                      sw.writeLine("StarlarkRuntimeStats.recordInst(opcode);");
                      sw.writeLine("++eval.localSteps;");
                    });
                // Now the switch by opcode
                genForOpcode(handlers, sw);
              });
          sw.writeLine("return Starlark.NONE;");
        });
    sw.writeLine("}");
  }

  private void genForOpcode(ImmutableList<Handler> handlers, SourceWriter sw) throws IOException {
    sw.switchBlock(
        "opcode",
        () -> {
          for (Handler handler : handlers) {
            sw.writeLineF("// %s", handler.annotation.opcode());
            sw.writeLineF("case %s:", handler.annotation.opcode().ordinal());
            sw.indented(
                () -> {
                  if (((ExecutableElement) handler.method).getReturnType().getKind()
                      != TypeKind.VOID) {
                    sw.writeLineF("return eval.%s();", handler.method.getSimpleName());
                  } else {
                    sw.writeLineF("eval.%s();", handler.method.getSimpleName());
                    if (handler.annotation.mayJump()) {
                      // Skip validateInstructionDecodedCorrectly()
                      sw.writeLine("continue;");
                    } else {
                      sw.writeLine("break;");
                    }
                  }
                });
          }
          sw.writeLine("default:");
          sw.indented(
              () -> {
                sw.writeLine("throw eval.otherOpcode();");
              });
        });
    sw.writeLine("eval.validateInstructionDecodedCorrectly();");
  }

  private void genUnimplementedOpcodesArray(ImmutableList<Handler> handlers, SourceWriter sw)
      throws IOException {
    sw.writeLine("// to test all cases covered");
    sw.writeLineF("static final %s[] UNIMPLEMENTED_OPCODES = {", BcOpcodeNumber.class.getName());
    sw.indented(
        () -> {
          for (BcOpcodeNumber number : BcOpcodeNumber.values()) {
            if (handlers.stream().noneMatch(h -> h.annotation.opcode() == number)) {
              sw.writeLineF("%s.%s,", BcOpcodeNumber.class.getName(), number);
            }
          }
        });
    sw.writeLine("};");
  }
}
