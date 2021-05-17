package net.starlark.java.annot.processor;

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
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
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
            ImmutableList<Method> methods =
                methodElements.stream()
                    .map(e -> new Method((ExecutableElement) e))
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

  private void genDescriptorImpl(SourceWriter sw, Method method) throws IOException {
    sw.writeLineF("private static class %s", innerClassName(method));
    sw.writeLineF("    extends net.starlark.java.eval.MethodDescriptorGenerated {");
    sw.indented(
        () -> {
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
        });
    sw.writeLine("}");
  }

  private static class Method {
    private final ExecutableElement method;
    private final StarlarkMethod annotation;

    public Method(ExecutableElement method) {
      this.method = method;
      this.annotation = method.getAnnotation(StarlarkMethod.class);
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

  private void genInvokeBody(SourceWriter sw, Method method) throws IOException {
    TypeElement classElement = (TypeElement) method.method.getEnclosingElement();

    StarlarkMethod starlarkMethod = method.annotation;

    boolean isStringModule =
        types.isSameType(classElement.asType(), starlarkTypeNames.stringModuleType);
    boolean isMethodLibrary =
        types.isSameType(classElement.asType(), starlarkTypeNames.methodLibraryType);

    int argsSize =
        method.method.getParameters().size()
            - (starlarkMethod.useStarlarkThread() ? 1 : 0)
            - (isStringModule ? 1 : 0);

    if (isStringModule) {
      sw.writeLineF(
          "net.starlark.java.eval.StringModule receiverTyped = net.starlark.java.eval.StringModule.INSTANCE;");
    } else if (isMethodLibrary) {
      sw.writeLineF(
          "net.starlark.java.eval.MethodLibrary receiverTyped = net.starlark.java.eval.MethodLibrary.INSTANCE;");
    } else {
      sw.writeLineF(
          "%s receiverTyped = (%s) receiver;",
          classElement.getQualifiedName(), classElement.getQualifiedName());
    }
    ArrayList<String> callArgs = new ArrayList<>();
    if (isStringModule) {
      callArgs.add("(String) receiver");
    }
    for (int i = 0; i != argsSize; ++i) {
      int index = i + (isStringModule ? 1 : 0);
      VariableElement p = method.method.getParameters().get(index);
      TypeMirror varType = this.types.erasure(p.asType());
      if (index < starlarkMethod.parameters().length) {
        // Otherwise it is varargs or kwargs or thread
        genCheckAllowedTypes(sw, starlarkMethod, varType, index, String.format("args[%s]", i));
      }
      sw.writeLineF("%s a%s = (%s) args[%s];", varType, i, varType, i);
      callArgs.add(String.format("a%s", i));
    }
    if (starlarkMethod.useStarlarkThread()) {
      callArgs.add("thread");
    }
    String callArgsFormatted = String.join(", ", callArgs);
    if (method.method.getReturnType().getKind() == TypeKind.VOID) {
      sw.writeLineF("receiverTyped.%s(%s);", method.method.getSimpleName(), callArgsFormatted);
    } else {
      sw.writeLineF(
          "%s r = receiverTyped.%s(%s);",
          this.types.erasure(method.method.getReturnType()),
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
    ArrayList<String> allowedTypes = new ArrayList<>();
    if (param.allowedTypes().length != 0) {
      for (ParamType paramType : param.allowedTypes()) {
        TypeMirror paramTypeType = StarlarkMethodProcessor.getParamTypeType(paramType);
        if (this.types.isSameType(paramTypeType, starlarkTypeNames.objectType)) {
          return;
        }
        exprs.add(String.format("!(%s instanceof %s)", expr, paramTypeType));
        allowedTypes.add(paramTypeType + ".class");
      }
    } else {
      if (this.types.isSameType(varType, starlarkTypeNames.objectType)) {
        return;
      } else if (varType.getKind() == TypeKind.BOOLEAN) {
        exprs.add(String.format("!(%s instanceof java.lang.Boolean)", expr));
        allowedTypes.add("java.lang.Boolean.class");
      } else {
        exprs.add(String.format("!(%s instanceof %s)", expr, varType));
        allowedTypes.add(varType + ".class");
      }
    }
    sw.ifBlock(
        String.join(" && ", exprs),
        () -> {
          sw.writeLineF(
              "throw notAllowedArgument(\"%s\", %s, new Class[] { %s });",
              param.name(), expr, String.join(", ", allowedTypes));
        });
  }

  // Reports a (formatted) error and fails the compilation.
  @FormatMethod
  private void errorf(Element e, String format, Object... args) {
    messager.printMessage(Diagnostic.Kind.ERROR, String.format(format, args), e);
  }
}
