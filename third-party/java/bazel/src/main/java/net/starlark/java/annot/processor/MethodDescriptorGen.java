package net.starlark.java.annot.processor;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.FormatMethod;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import net.starlark.java.annot.FnPurity;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkGeneratedFiles;
import net.starlark.java.annot.StarlarkMethod;

/** Generator for {@link net.starlark.java.eval.MethodDescriptorGenerated}. */
class MethodDescriptorGen {

  private final StarlarkTypeNames starlarkTypeNames;
  private final Types types;
  private final Elements elements;
  private final Filer filer;
  private final Messager messager;

  MethodDescriptorGen(ProcessingEnvironment env, StarlarkTypeNames starlarkTypeNames) {
    this.types = env.getTypeUtils();
    this.elements = env.getElementUtils();
    this.filer = env.getFiler();
    this.messager = env.getMessager();
    this.starlarkTypeNames = starlarkTypeNames;
  }

  private static String generatedClassLocalName(TypeElement type) {
    String name = type.getSimpleName().toString();
    while (type.getEnclosingElement() instanceof TypeElement) {
      type = (TypeElement) type.getEnclosingElement();
      name = type.getSimpleName() + "_" + name;
    }
    return name + StarlarkGeneratedFiles.GENERATED_CLASS_NAME_SUFFIX;
  }

  void genBuiltins(TypeElement classElement, List<Element> methodElements) {
    String builtinsName = generatedClassLocalName(classElement);
    String builtinsFqn = this.elements.getPackageOf(classElement) + "." + builtinsName;
    try {
      JavaFileObject classFile = this.filer.createSourceFile(builtinsFqn, classElement);
      Writer writer = classFile.openWriter();
      SourceWriter sw = new SourceWriter(writer);
      sw.writeLineF("package %s;", this.elements.getPackageOf(classElement).getQualifiedName());
      sw.writeLine("");
      sw.writeLineF(
          "// @javax.annotation.Generated(\"%s\")", StarlarkMethodProcessor.class.getName());
      sw.writeLine("@java.lang.SuppressWarnings({\"all\"})");
      sw.writeLine("public class " + builtinsName + " {");
      sw.indented(
          () -> {
            boolean isStringModule =
                types.isSameType(classElement.asType(), starlarkTypeNames.stringModuleType);
            boolean isMethodLibrary =
                types.isSameType(classElement.asType(), starlarkTypeNames.methodLibraryType);

            ImmutableList<Method> methods =
                methodElements.stream()
                    .map(e -> new Method((ExecutableElement) e, isStringModule, isMethodLibrary))
                    .collect(ImmutableList.toImmutableList());

            for (Method method : methods) {
              genDescriptorImpl(sw, method);
            }
            sw.writeLine("");
            sw.writeLine(
                "public static net.starlark.java.eval.MethodDescriptorGenerated[] HANDLERS = {");
            for (Method method : methods) {
              sw.writeLineF("new %s(),", innerClassName(method));
            }
            sw.writeLine("};");
          });
      sw.writeLine("}");
      writer.flush();
      writer.close();
    } catch (IOException e) {
      errorf(classElement, "Failed to write class file %s: %s", classElement, e);
    }
  }

  private String innerClassName(Method method) {
    return String.format("Desc_%s", method.annotation.name());
  }

  private String quoteJavaString(String s) {
    // Seems like there's a bug in ecj which quotes this string incorrectly, so we can't use
    // ```
    // return elements.getConstantExpression(s);
    // ```
    // TODO: escape other non-printable characters.
    //   Not-escaping them would result in compilation error (generated code will be invalid).
    return '"'
        + s.replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\"", "\\\"")
        + '"';
  }

  private String evalDefaultExpression(String paramName, String expr) {
    // Eval certain expression at codegen time for faster Java startup.
    // We could evaluate all of them, but we don't have access to Starlark runtime
    // in annotation processor.
    switch (expr) {
      case "None":
        return "net.starlark.java.eval.Starlark.NONE";
      case "True":
        return "true";
      case "False":
        return "false";
      case "unbound":
        return "net.starlark.java.eval.Starlark.UNBOUND";
      case "[]":
        return "net.starlark.java.eval.StarlarkList.empty()";
      case "()":
        return "net.starlark.java.eval.Tuple.empty()";
      case "''":
        return "\"\"";
      default:
        if (expr.matches("\\d{1,10}")) {
          return String.format("net.starlark.java.eval.StarlarkInt.of(%s)", expr);
        } else {
          return String.format("evalDefault(\"%s\", %s)", paramName, quoteJavaString(expr));
        }
    }
  }

  private void genDescriptorImpl(SourceWriter sw, Method method) throws IOException {
    sw.writeLineF("private static class %s", innerClassName(method));
    sw.writeLineF("    extends net.starlark.java.eval.MethodDescriptorGenerated {");
    sw.indented(
        () -> {
          Param[] parameters = method.annotation.parameters();
          for (int i = 0; i < parameters.length; i++) {
            Param param = parameters[i];
            if (param.defaultValue().isEmpty()) {
              continue;
            }
            VariableElement p = method.method.getParameters().get(i);
            TypeMirror paramType = this.types.erasure(p.asType());
            sw.writeLineF(
                "private static final %s P%s_DEFAULT = (%s) %s;",
                paramType, i, paramType, evalDefaultExpression(param.name(), param.defaultValue()));
          }

          sw.writeLineF("%s() {", innerClassName(method));
          sw.indented(
              () -> {
                sw.writeLineF(
                    "super(\"%s\", \"%s\");",
                    method.method.getSimpleName(), method.annotation.name());
              });
          sw.writeLine("}");
          sw.writeLine("");
          genInvoke(sw, method);
          sw.writeLine("");
          genInvokePos(sw, method);
        });
    sw.writeLine("}");
  }

  private static class Method {
    private final ExecutableElement method;
    private final StarlarkMethod annotation;
    private final boolean isStringModule;
    private final boolean isMethodLibrary;

    Method(ExecutableElement method, boolean isStringModule, boolean isMethodLibrary) {
      this.method = method;
      this.annotation = method.getAnnotation(StarlarkMethod.class);
      this.isStringModule = isStringModule;
      this.isMethodLibrary = isMethodLibrary;
    }

    /** Number of positional parameters. */
    int numPositional() {
      Param[] parameters = annotation.parameters();
      for (int i = 0; i < parameters.length; i++) {
        Param parameter = parameters[i];
        if (!parameter.positional()) {
          return i;
        }
      }
      return parameters.length;
    }

    int numPositionalWithoutSelf() {
      return numPositional() - (isStringModule ? 1 : 0);
    }

    /** Number of required positional parameters (without default values). */
    int numRequiredPositional() {
      Param[] parameters = annotation.parameters();
      for (int i = 0; i < parameters.length; i++) {
        Param parameter = parameters[i];
        if (!parameter.positional() || !parameter.defaultValue().isEmpty()) {
          return i;
        }
      }
      return parameters.length;
    }

    /** Number of parameter without self parameter which used for strings. */
    int numRequiredPositionalWithoutSelf() {
      return numRequiredPositional() - (isStringModule ? 1 : 0);
    }

    /** Number of required parameter without self parameter which used for strings. */
    int numRequiredPositionalArgs() {
      return numRequiredPositional() - (isStringModule ? 1 : 0);
    }

    TypeElement enclosingType() {
      return (TypeElement) method.getEnclosingElement();
    }

    /** Index of {@code **kwargs} parameter or {@code -1}. */
    int kwargsIndex() {
      if (annotation.extraKeywords().name().isEmpty()) {
        return -1;
      }
      int p = method.getParameters().size();
      if (annotation.useStarlarkThread()) {
        --p;
      }
      return p - 1;
    }

    /** Index of {@code **args} parameter or {@code -1}. */
    int varargsIndex() {
      if (annotation.extraPositionals().name().isEmpty()) {
        return -1;
      }
      int p = method.getParameters().size();
      if (annotation.useStarlarkThread()) {
        --p;
      }
      if (!annotation.extraKeywords().name().isEmpty()) {
        --p;
      }
      return p - 1;
    }

    /** Check if function cannot be called with positional-only arguments. */
    boolean posOnlyAlwaysFails() {
      for (Param param : annotation.parameters()) {
        if (!param.positional() && param.defaultValue().isEmpty()) {
          return true;
        }
      }
      return false;
    }
  }

  private void genInvoke(SourceWriter sw, Method method) throws IOException {
    sw.writeLine("@java.lang.Override");
    sw.writeLine(
        "public Object invoke(java.lang.Object receiver, java.lang.Object[] args, net.starlark.java.eval.StarlarkThread thread)");
    sw.writeLine("    throws java.lang.Exception {");
    sw.indented(() -> genInvokeBody(sw, method));
    sw.writeLine("}");
  }

  private void genInvokePos(SourceWriter sw, Method method) throws IOException {
    sw.writeLine("@java.lang.Override");
    sw.writeLine(
        "public Object invokePos(java.lang.Object receiver, java.lang.Object[] args, net.starlark.java.eval.StarlarkThread thread)");
    sw.writeLine("    throws java.lang.Exception {");
    sw.indented(() -> genInvokePosBody(sw, method));
    sw.writeLine("}");
  }

  /** Assign to {@code receiverTyped} variable if necessary. */
  private void writeAssignToReceiverTyped(SourceWriter sw, Method method) throws IOException {
    if (method.isStringModule || method.isMethodLibrary) {
      Preconditions.checkState(
          method.method.getModifiers().contains(Modifier.STATIC),
          "builtin method must be static: %s",
          method.method);
    } else {
      if (!method.method.getModifiers().contains(Modifier.STATIC)) {
        sw.writeLineF(
            "%s receiverTyped = (%s) receiver;",
            method.enclosingType().getQualifiedName(), method.enclosingType().getQualifiedName());
      }
    }
  }

  /** Expression of builtin function receiver, either class name or variable name. */
  private String receiverTypedExpr(Method method) {
    if (method.method.getModifiers().contains(Modifier.STATIC)) {
      return method.enclosingType().getQualifiedName().toString();
    } else {
      return "receiverTyped";
    }
  }

  private interface ParamWriter {
    void write(int argIndex, int paramIndex, TypeMirror varType) throws IOException;
  }

  private void forEachParam(SourceWriter sw, Method method, ParamWriter paramWriter)
      throws IOException {
    StarlarkMethod starlarkMethod = method.annotation;

    writeAssignToReceiverTyped(sw, method);

    ArrayList<String> callArgs = new ArrayList<>();
    if (method.isStringModule) {
      callArgs.add("(String) receiver");
    }

    int argsSize =
        method.method.getParameters().size()
            - (method.annotation.useStarlarkThread() ? 1 : 0)
            - (method.isStringModule ? 1 : 0);

    for (int argIndex = 0; argIndex != argsSize; ++argIndex) {
      int paramIndex = argIndex + (method.isStringModule ? 1 : 0);
      VariableElement p = method.method.getParameters().get(paramIndex);
      TypeMirror varType = this.types.erasure(p.asType());
      sw.writeLineF("%s a%s;", varType, argIndex);
      paramWriter.write(argIndex, paramIndex, varType);
      callArgs.add(String.format("a%s", argIndex));
    }

    if (starlarkMethod.useStarlarkThread()) {
      callArgs.add("thread");
    }
    String callArgsFormatted = String.join(", ", callArgs);
    if (method.method.getReturnType().getKind() == TypeKind.VOID) {
      sw.writeLineF(
          "%s.%s(%s);",
          receiverTypedExpr(method), method.method.getSimpleName(), callArgsFormatted);
    } else {
      sw.writeLineF(
          "%s r = %s.%s(%s);",
          this.types.erasure(method.method.getReturnType()),
          receiverTypedExpr(method),
          method.method.getSimpleName(),
          callArgsFormatted);
    }
    if (method.method.getReturnType().getKind() == TypeKind.VOID) {
      sw.writeLine("return net.starlark.java.eval.Starlark.NONE;");
    } else if (method.method.getReturnType().getKind() == TypeKind.INT) {
      sw.writeLine("return net.starlark.java.eval.StarlarkInt.of(r);");
    } else if (method.method.getReturnType().getKind() == TypeKind.BOOLEAN) {
      sw.writeLine("return r;");
    } else {
      if (!starlarkMethod.trustReturnsValid() || starlarkMethod.allowReturnNones()) {
        sw.ifBlock(
            "r == null",
            () -> {
              if (starlarkMethod.allowReturnNones()) {
                sw.writeLine("return net.starlark.java.eval.Starlark.NONE;");
              } else {
                sw.writeLine("throw methodInvocationReturnedNull(args);");
              }
            });
      }
      if (needToCallFromJava(method)) {
        sw.writeLine("return net.starlark.java.eval.Starlark.fromJava(r, thread.mutability());");
      } else {
        sw.writeLine("return r;");
      }
    }
  }

  private void writeArgBindException(SourceWriter sw) throws IOException {
    sw.writeLine("throw new ArgumentBindException();");
  }

  private void writeArgBinExceptionIf(SourceWriter sw, String cond, Object... args)
      throws IOException {
    sw.ifBlock(String.format(cond, args), () -> writeArgBindException(sw));
  }

  private void writeRecordSideEffect(SourceWriter sw, Method method) throws IOException {
    if (method.annotation.purity() == FnPurity.DEFAULT) {
      sw.writeLine("recordSideEffect(thread);");
    }
  }

  private void genInvokeBody(SourceWriter sw, Method method) throws IOException {
    writeRecordSideEffect(sw, method);

    StarlarkMethod starlarkMethod = method.annotation;

    forEachParam(
        sw,
        method,
        (argIndex, paramIndex, varType) -> {
          if (paramIndex != method.varargsIndex() && paramIndex != method.kwargsIndex()) {
            Param param = starlarkMethod.parameters()[paramIndex];
            if (param.defaultValue().isEmpty()) {
              sw.ifBlock(
                  String.format("args[%s] == null", argIndex),
                  () -> {
                    writeArgBindException(sw);
                  });
              genCheckAllowedTypes(
                  sw, starlarkMethod, varType, paramIndex, String.format("args[%s]", argIndex));
              sw.writeLineF("a%s = (%s) args[%s];", argIndex, varType, argIndex);
            } else {
              sw.ifElse(
                  String.format("args[%s] == null", argIndex),
                  () -> {
                    sw.writeLineF("a%s = P%s_DEFAULT;", argIndex, paramIndex);
                  },
                  () -> {
                    genCheckAllowedTypes(
                        sw,
                        starlarkMethod,
                        varType,
                        paramIndex,
                        String.format("args[%s]", argIndex));
                    sw.writeLineF("a%s = (%s) args[%s];", argIndex, varType, argIndex);
                  });
            }
          } else {
            // Varargs or kwargs
            sw.writeLineF("a%s = (%s) args[%s];", argIndex, varType, argIndex);
          }
        });
  }

  private void genInvokePosBody(SourceWriter sw, Method method) throws IOException {
    if (method.posOnlyAlwaysFails()) {
      // Skip function generation if it cannot be positional only.
      writeArgBindException(sw);
      // We have to stop code generation here, otherwise code above will generate
      // unreachable statements, and the java compilation will fail.
      return;
    }

    writeRecordSideEffect(sw, method);

    StarlarkMethod starlarkMethod = method.annotation;

    ArrayList<String> argsLengthConds = new ArrayList<>();
    if (method.annotation.extraPositionals().name().isEmpty()) {
      // there's no *args
      if (method.numPositionalWithoutSelf() == method.numRequiredPositionalWithoutSelf()) {
        argsLengthConds.add(String.format("args.length != %s", method.numPositionalWithoutSelf()));
      } else {
        if (method.numRequiredPositionalWithoutSelf() != 0) {
          argsLengthConds.add(
              String.format("args.length < %s", method.numRequiredPositionalWithoutSelf()));
        }
        argsLengthConds.add(String.format("args.length > %s", method.numPositionalWithoutSelf()));
      }
    } else {
      // there's *args
      if (method.numRequiredPositionalWithoutSelf() != 0) {
        argsLengthConds.add(
            String.format("args.length < %s", method.numRequiredPositionalWithoutSelf()));
      }
    }
    if (!argsLengthConds.isEmpty()) {
      writeArgBinExceptionIf(sw, String.join(" || ", argsLengthConds));
    }

    forEachParam(
        sw,
        method,
        (argIndex, paramIndex, varType) -> {
          if (paramIndex == method.varargsIndex()) {
            sw.writeLineF(
                "a%s = tupleFromRemArgs(args, %s);", argIndex, method.numPositionalWithoutSelf());
          } else if (paramIndex == method.kwargsIndex()) {
            sw.writeLineF("a%s = net.starlark.java.eval.Dict.of(thread.mutability());", argIndex);
          } else {
            Param param = starlarkMethod.parameters()[paramIndex];
            if (!param.positional()) {
              // named-only parameter
              sw.writeLineF("a%s = P%s_DEFAULT;", argIndex, paramIndex);
            } else if (paramIndex < method.numRequiredPositional()) {
              // index is valid, checked above
              genCheckAllowedTypes(
                  sw, starlarkMethod, varType, paramIndex, String.format("args[%s]", argIndex));
              sw.writeLineF("a%s = (%s) args[%s];", argIndex, varType, argIndex);
            } else {
              // positional parameter
              sw.ifElse(
                  String.format("args.length <= %s", argIndex),
                  () -> {
                    if (!param.defaultValue().isEmpty()) {
                      sw.writeLineF("a%s = P%s_DEFAULT;", argIndex, paramIndex);
                    } else {
                      writeArgBindException(sw);
                    }
                  },
                  () -> {
                    genCheckAllowedTypes(
                        sw,
                        starlarkMethod,
                        varType,
                        paramIndex,
                        String.format("args[%s]", argIndex));
                    sw.writeLineF("a%s = (%s) args[%s];", argIndex, varType, argIndex);
                  });
            }
          }
        });
  }

  private boolean needToCallFromJava(Method method) {
    TypeMirror returnType = method.method.getReturnType();
    StarlarkMethod starlarkMethod = method.annotation;
    return !starlarkMethod.trustReturnsValid()
        && !this.types.isSameType(returnType, starlarkTypeNames.booleanType)
        && !this.types.isSameType(returnType, starlarkTypeNames.stringType)
        && !this.types.isAssignable(returnType, starlarkTypeNames.starlarkValueType);
  }

  private void genCheckAllowedTypes(
      SourceWriter sw, StarlarkMethod starlarkMethod, TypeMirror varType, int index, String expr)
      throws IOException {

    Param param = starlarkMethod.parameters()[index];
    ArrayList<String> exprs = new ArrayList<>();
    if (param.allowedTypes().length != 0) {
      for (ParamType paramType : param.allowedTypes()) {
        TypeMirror paramTypeType = StarlarkMethodProcessor.getParamTypeType(paramType);
        if (this.types.isSameType(paramTypeType, starlarkTypeNames.objectType)) {
          return;
        }
        exprs.add(String.format("!(%s instanceof %s)", expr, paramTypeType));
      }
    } else {
      if (this.types.isSameType(varType, starlarkTypeNames.objectType)) {
        return;
      } else if (varType.getKind() == TypeKind.BOOLEAN) {
        exprs.add(String.format("!(%s instanceof java.lang.Boolean)", expr));
      } else {
        exprs.add(String.format("!(%s instanceof %s)", expr, varType));
      }
    }
    sw.ifBlock(String.join(" && ", exprs), () -> writeArgBindException(sw));
  }

  // Reports a (formatted) error and fails the compilation.
  @FormatMethod
  private void errorf(Element e, String format, Object... args) {
    messager.printMessage(Diagnostic.Kind.ERROR, String.format(format, args), e);
  }
}
