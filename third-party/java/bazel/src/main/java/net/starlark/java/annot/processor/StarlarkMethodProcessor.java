// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package net.starlark.java.annot.processor;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.errorprone.annotations.FormatMethod;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkGeneratedFiles;
import net.starlark.java.annot.StarlarkMethod;

/**
 * Annotation processor for {@link StarlarkMethod}. See that class for requirements.
 *
 * <p>These properties can be relied upon at runtime without additional checks.
 */
@SupportedAnnotationTypes({
  "net.starlark.java.annot.StarlarkMethod",
  "net.starlark.java.annot.StarlarkBuiltin",
  "net.starlark.java.annot.internal.BcEvalHandler",
})
public class StarlarkMethodProcessor extends AbstractProcessor {

  private BcEvalDispatchGen bcEvalDispatchGen;

  private Types types;
  private Elements elements;
  private Messager messager;
  private Filer filer;

  // A set containing a TypeElement for each class with a StarlarkMethod.selfCall annotation.
  private Set<Element> classesWithSelfcall;
  // A multimap where keys are class element, and values are the callable method names identified in
  // that class (where "method name" is StarlarkMethod.name).
  private SetMultimap<Element, String> processedClassMethods;

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public synchronized void init(ProcessingEnvironment env) {
    super.init(env);
    this.types = env.getTypeUtils();
    this.elements = env.getElementUtils();
    this.messager = env.getMessager();
    this.filer = env.getFiler();
    this.classesWithSelfcall = new HashSet<>();
    this.processedClassMethods = LinkedHashMultimap.create();
    this.bcEvalDispatchGen = new BcEvalDispatchGen(env);
  }

  private TypeMirror getType(String canonicalName) {
    return elements.getTypeElement(canonicalName).asType();
  }

  private static class StarlarkTypeNames {
    final TypeMirror objectType;
    final TypeMirror stringType;
    final TypeMirror integerType;
    final TypeMirror booleanType;
    final TypeMirror listType;
    final TypeMirror mapType;
    final TypeMirror starlarkValueType;
    private final TypeMirror stringModuleType;
    private final TypeMirror methodLibraryType;

    public StarlarkTypeNames(
        TypeMirror objectType,
        TypeMirror stringType,
        TypeMirror integerType,
        TypeMirror booleanType,
        TypeMirror listType,
        TypeMirror mapType,
        TypeMirror starlarkValueType,
        TypeMirror stringModuleType,
        TypeMirror methodLibraryType) {
      this.objectType = objectType;
      this.stringType = stringType;
      this.integerType = integerType;
      this.booleanType = booleanType;
      this.listType = listType;
      this.mapType = mapType;
      this.starlarkValueType = starlarkValueType;
      this.stringModuleType = stringModuleType;
      this.methodLibraryType = methodLibraryType;
    }
  }

  private StarlarkTypeNames makeTypeNames() {
    TypeMirror objectType = getType("java.lang.Object");
    TypeMirror stringType = getType("java.lang.String");
    TypeMirror integerType = getType("java.lang.Integer");
    TypeMirror booleanType = getType("java.lang.Boolean");
    TypeMirror listType = getType("java.util.List");
    TypeMirror mapType = getType("java.util.Map");
    TypeMirror starlarkValueType = getType("net.starlark.java.eval.StarlarkValue");
    TypeMirror stringModuleType = getType("net.starlark.java.eval.StringModule");
    TypeMirror methodLibraryType = getType("net.starlark.java.eval.MethodLibrary");
    return new StarlarkTypeNames(
        objectType,
        stringType,
        integerType,
        booleanType,
        listType,
        mapType,
        starlarkValueType,
        stringModuleType,
        methodLibraryType);
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    StarlarkTypeNames typeNames = makeTypeNames();

    if (roundEnv.processingOver()) {
      return false;
    }

    // Ensure StarlarkBuiltin-annotated classes implement StarlarkValue.
    for (Element cls : roundEnv.getElementsAnnotatedWith(StarlarkBuiltin.class)) {
      if (cls.getKind().isInterface()) {
        // Interfaces cannot extend `StarlarkValue` so we cannot test it
        continue;
      }
      if (!types.isAssignable(cls.asType(), typeNames.starlarkValueType)) {
        errorf(
            cls,
            "class %s has StarlarkBuiltin annotation but does not implement StarlarkValue",
            cls.getSimpleName());
      }
    }

    Map<Element, List<Element>> methodsByEncosing =
        roundEnv.getElementsAnnotatedWith(StarlarkMethod.class).stream()
            .collect(Collectors.groupingBy(Element::getEnclosingElement));

    for (Map.Entry<Element, List<Element>> entry : methodsByEncosing.entrySet()) {
      TypeElement classElement = (TypeElement) entry.getKey();
      for (Element method : entry.getValue()) {
        processElement(typeNames, classElement, method);
      }

      generateBuiltins(classElement, entry.getValue(), typeNames);
    }

    bcEvalDispatchGen.gen(roundEnv);

    // Returning false allows downstream processors to work on the same annotations
    return false;
  }

  private void generateBuiltins(
      TypeElement classElement, List<Element> methods, StarlarkTypeNames typeNames) {
    String builtinsName = generatedClassLocalName(classElement);
    String builtinsFqn = elements.getPackageOf(classElement) + "." + builtinsName;
    try {
      JavaFileObject classFile = filer.createSourceFile(builtinsFqn, classElement);
      Writer writer = classFile.openWriter();
      SourceWriter sw = new SourceWriter(writer);
      sw.writeLineF("package %s;", elements.getPackageOf(classElement).getQualifiedName());
      sw.writeLine("");
      sw.writeLineF("// @javax.annotation.Generated(\"%s\")", this.getClass().getName());
      sw.writeLine("@java.lang.SuppressWarnings({\"all\"})");
      sw.writeLine("public class " + builtinsName + " {");
      sw.indented(
          () -> {
            sw.writeLine(
                "public static net.starlark.java.eval.MethodDescriptorGenerated[] HANDLERS = {");
            for (Element element : methods) {
              generateMethod(sw, classElement, (ExecutableElement) element, typeNames);
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

  private void generateMethod(
      SourceWriter sw,
      TypeElement classElement,
      ExecutableElement method,
      StarlarkTypeNames typeNames)
      throws IOException {
    boolean isStringModule = types.isSameType(classElement.asType(), typeNames.stringModuleType);
    boolean isMethodLibrary = types.isSameType(classElement.asType(), typeNames.methodLibraryType);

    StarlarkMethod starlarkMethod = method.getAnnotation(StarlarkMethod.class);
    sw.writeLineF(
        "new net.starlark.java.eval.MethodDescriptorGenerated(\"%s\", \"%s\") {",
        method.getSimpleName(), starlarkMethod.name());
    sw.indented(
        () -> {
          sw.writeLine("@java.lang.Override");
          sw.writeLine(
              "public Object invoke(java.lang.Object receiver, java.lang.Object[] args, net.starlark.java.eval.StarlarkThread thread)");
          sw.indented(
              () -> {
                sw.indented(
                    () -> {
                      sw.writeLine("throws java.lang.Exception {");
                    });
                int argsSize =
                    method.getParameters().size()
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
                  VariableElement p = method.getParameters().get(index);
                  TypeMirror varType = types.erasure(p.asType());
                  writeCheckAllowedTypes(
                      sw, typeNames, starlarkMethod, varType, index, String.format("args[%s]", i));
                  sw.writeLineF("%s a%s = (%s) args[%s];", varType, i, varType, i);
                  callArgs.add(String.format("a%s", i));
                }
                if (starlarkMethod.useStarlarkThread()) {
                  callArgs.add("thread");
                }
                String callArgsFormatted = String.join(", ", callArgs);
                if (method.getReturnType().getKind() == TypeKind.VOID) {
                  sw.writeLineF("receiverTyped.%s(%s);", method.getSimpleName(), callArgsFormatted);
                } else {
                  sw.writeLineF(
                      "%s r = receiverTyped.%s(%s);",
                      types.erasure(method.getReturnType()),
                      method.getSimpleName(),
                      callArgsFormatted);
                }
                if (method.getReturnType().getKind() == TypeKind.VOID) {
                  sw.writeLine("return net.starlark.java.eval.Starlark.NONE;");
                } else if (method.getReturnType().getKind() == TypeKind.INT) {
                  sw.writeLine("return net.starlark.java.eval.StarlarkInt.of(r);");
                } else if (method.getReturnType().getKind() == TypeKind.BOOLEAN) {
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
                  if (needToCallFromJava(starlarkMethod, method.getReturnType(), typeNames)) {
                    sw.writeLine(
                        "return net.starlark.java.eval.Starlark.fromJava(r, thread.mutability());");
                  } else {
                    sw.writeLine("return r;");
                  }
                }
              });
          sw.writeLine("}");
        });
    sw.writeLine("},");
  }

  private boolean needToCallFromJava(
      StarlarkMethod starlarkMethod, TypeMirror returnType, StarlarkTypeNames typeNames) {
    return !starlarkMethod.trustReturnsValid()
        && !types.isSameType(returnType, typeNames.booleanType)
        && !types.isSameType(returnType, typeNames.stringType)
        && !types.isAssignable(returnType, typeNames.starlarkValueType);
  }

  private void writeCheckAllowedTypes(
      SourceWriter sw,
      StarlarkTypeNames typeNames,
      StarlarkMethod starlarkMethod,
      TypeMirror varType,
      int index,
      String expr)
      throws IOException {
    if (index >= starlarkMethod.parameters().length) {
      return;
    }

    Param param = starlarkMethod.parameters()[index];
    ArrayList<String> exprs = new ArrayList<>();
    ArrayList<String> allowedTypes = new ArrayList<>();
    if (param.allowedTypes().length != 0) {
      for (ParamType paramType : param.allowedTypes()) {
        TypeMirror paramTypeType = getParamTypeType(paramType);
        if (types.isSameType(paramTypeType, typeNames.objectType)) {
          return;
        }
        exprs.add(String.format("!(%s instanceof %s)", expr, paramTypeType));
        allowedTypes.add(paramTypeType + ".class");
      }
    } else {
      if (types.isSameType(varType, typeNames.objectType)) {
        return;
      } else if (varType.getKind() == TypeKind.BOOLEAN) {
        exprs.add(String.format("!(%s instanceof java.lang.Boolean)", expr));
        allowedTypes.add("java.lang.Boolean.class");
      } else {
        exprs.add(String.format("!(%s instanceof %s)", expr, varType));
        allowedTypes.add(varType + ".class");
      }
    }
    sw.writeLineF("if (%s) {", String.join(" && ", exprs));
    sw.indented(
        () -> {
          sw.writeLineF(
              "throw notAllowedArgument(\"%s\", %s, new Class[] { %s });",
              param.name(), expr, String.join(", ", allowedTypes));
        });
    sw.writeLine("}");
  }

  private static String generatedClassLocalName(TypeElement type) {
    String name = type.getSimpleName().toString();
    while (type.getEnclosingElement() instanceof TypeElement) {
      type = (TypeElement) type.getEnclosingElement();
      name = type.getSimpleName() + "_" + name;
    }
    return name + StarlarkGeneratedFiles.GENERATED_CLASS_NAME_SUFFIX;
  }

  private void processElement(
      StarlarkTypeNames typeNames, Element classOrInterface, Element element) {
    // Only methods are annotated with StarlarkMethod.
    // This is ensured by the @Target(ElementType.METHOD) annotation.
    ExecutableElement method = (ExecutableElement) element;
    if (!method.getModifiers().contains(Modifier.PUBLIC)) {
      errorf(method, "StarlarkMethod-annotated methods must be public.");
    }
    if (method.getModifiers().contains(Modifier.STATIC)) {
      errorf(method, "StarlarkMethod-annotated methods cannot be static.");
    }

    // Check the annotation itself.
    StarlarkMethod annot = method.getAnnotation(StarlarkMethod.class);
    if (annot.name().isEmpty()) {
      errorf(method, "StarlarkMethod.name must be non-empty.");
    }
    Element cls = method.getEnclosingElement();
    if (!processedClassMethods.put(cls, annot.name())) {
      errorf(method, "Containing class defines more than one method named '%s'.", annot.name());
    }
    if (annot.documented() && annot.doc().isEmpty()) {
      errorf(method, "The 'doc' string must be non-empty if 'documented' is true.");
    }
    if (annot.structField()) {
      checkStructFieldAnnotation(method, annot);
    }
    if (annot.selfCall() && !classesWithSelfcall.add(cls)) {
      errorf(method, "Containing class has more than one selfCall method defined.");
    }

    if (annot.allowReturnNones() != (method.getAnnotation(Nullable.class) != null)) {
      errorf(method, "Method must be annotated with @Nullable iff allowReturnNones is set.");
    }

    checkParameters(method, annot);

    // Verify that result type, if final, might satisfy Starlark.fromJava.
    // (If the type is non-final we can't prove that all subclasses are invalid.)
    TypeMirror ret = method.getReturnType();
    if (ret.getKind() == TypeKind.DECLARED) {
      DeclaredType obj = (DeclaredType) ret;
      if (obj.asElement().getModifiers().contains(Modifier.FINAL)
          && !types.isSameType(ret, typeNames.stringType)
          && !types.isSameType(ret, typeNames.integerType)
          && !types.isSameType(ret, typeNames.booleanType)
          && !types.isAssignable(obj, typeNames.starlarkValueType)
          && !types.isAssignable(obj, typeNames.listType)
          && !types.isAssignable(obj, typeNames.mapType)) {
        errorf(
            method,
            "StarlarkMethod-annotated method %s returns %s, which has no legal Starlark values"
                + " (see Starlark.fromJava)",
            method.getSimpleName(),
            ret);
      }
    }
  }

  // TODO(adonovan): obviate these checks by separating field/method interfaces.
  private void checkStructFieldAnnotation(ExecutableElement method, StarlarkMethod annot) {
    // useStructField is incompatible with special thread-related parameters,
    // because unlike a method, which is actively called within a thread,
    // a field is a passive part of a data structure that may be accessed
    // from Java threads that don't have anything to do with Starlark threads.
    // However, the StarlarkSemantics is available even to fields,
    // because it is a required parameter for all attribute-selection
    // operations x.f.
    //
    // Not having a thread forces implementations to assume Mutability=null,
    // which is not quite right. Perhaps one day we can abolish Mutability
    // in favor of a tracing approach as in go.starlark.net.
    if (annot.useStarlarkThread()) {
      errorf(
          method,
          "a StarlarkMethod-annotated method with structField=true may not also specify"
              + " useStarlarkThread");
    }
    if (!annot.extraPositionals().name().isEmpty()) {
      errorf(
          method,
          "a StarlarkMethod-annotated method with structField=true may not also specify"
              + " extraPositionals");
    }
    if (!annot.extraKeywords().name().isEmpty()) {
      errorf(
          method,
          "a StarlarkMethod-annotated method with structField=true may not also specify"
              + " extraKeywords");
    }
    if (annot.selfCall()) {
      errorf(
          method,
          "a StarlarkMethod-annotated method with structField=true may not also specify"
              + " selfCall=true");
    }
    int nparams = annot.parameters().length;
    if (nparams > 0) {
      errorf(
          method,
          "method %s is annotated structField=true but also has %d Param annotations",
          method.getSimpleName(),
          nparams);
    }
  }

  private void checkParameters(ExecutableElement method, StarlarkMethod annot) {
    List<? extends VariableElement> params = method.getParameters();

    boolean allowPositionalNext = true;
    boolean allowPositionalOnlyNext = true;
    boolean allowNonDefaultPositionalNext = true;
    boolean hasUndocumentedMethods = false;

    // Check @Param annotations match parameters.
    Param[] paramAnnots = annot.parameters();
    for (int i = 0; i < paramAnnots.length; i++) {
      Param paramAnnot = paramAnnots[i];
      if (i >= params.size()) {
        errorf(
            method,
            "method %s has %d Param annotations but only %d parameters",
            method.getSimpleName(),
            paramAnnots.length,
            params.size());
        return;
      }
      VariableElement param = params.get(i);

      checkParameter(param, paramAnnot);

      // Check parameter ordering.
      if (paramAnnot.positional()) {
        if (!allowPositionalNext) {
          errorf(
              param,
              "Positional parameter '%s' is specified after one or more non-positional parameters",
              paramAnnot.name());
        }
        if (!paramAnnot.named() && !allowPositionalOnlyNext) {
          errorf(
              param,
              "Positional-only parameter '%s' is specified after one or more named or undocumented"
                  + " parameters",
              paramAnnot.name());
        }
        if (paramAnnot.defaultValue().isEmpty()) { // There is no default value.
          if (!allowNonDefaultPositionalNext) {
            errorf(
                param,
                "Positional parameter '%s' has no default value but is specified after one "
                    + "or more positional parameters with default values",
                paramAnnot.name());
          }
        } else { // There is a default value.
          // No positional parameters without a default value can come after this parameter.
          allowNonDefaultPositionalNext = false;
        }
      } else { // Not positional.
        // No positional parameters can come after this parameter.
        allowPositionalNext = false;

        if (!paramAnnot.named()) {
          errorf(param, "Parameter '%s' must be either positional or named", paramAnnot.name());
        }
      }
      if (!paramAnnot.documented()) {
        hasUndocumentedMethods = true;
      }
      if (paramAnnot.named() || !paramAnnot.documented()) {
        // No positional-only parameters can come after this parameter.
        allowPositionalOnlyNext = false;
      }
    }

    if (hasUndocumentedMethods && !annot.extraKeywords().name().isEmpty()) {
      errorf(
          method,
          "Method '%s' has undocumented parameters but also allows extra keyword parameters",
          annot.name());
    }
    checkSpecialParams(method, annot);
  }

  // Checks consistency of a single parameter with its Param annotation.
  private void checkParameter(Element param, Param paramAnnot) {
    TypeMirror paramType = param.asType(); // type of the Java method parameter

    // Give helpful hint for parameter of type Integer.
    TypeMirror integerType = getType("java.lang.Integer");
    if (types.isSameType(paramType, integerType)) {
      errorf(
          param,
          "use StarlarkInt, not Integer for parameter '%s' (and see Starlark.toInt)",
          paramAnnot.name());
    }

    // Reject an entry of Param.allowedTypes if not assignable to the parameter variable.
    for (ParamType paramTypeAnnot : paramAnnot.allowedTypes()) {
      TypeMirror t = getParamTypeType(paramTypeAnnot);
      if (!types.isAssignable(t, types.erasure(paramType))) {
        errorf(
            param,
            "annotated allowedTypes entry %s of parameter '%s' is not assignable to variable of "
                + "type %s",
            t,
            paramAnnot.name(),
            paramType);
      }
    }

    // Reject generic types C<T> other than C<?>,
    // since reflective calls check only the toplevel class.
    if (paramType instanceof DeclaredType) {
      DeclaredType declaredType = (DeclaredType) paramType;
      for (TypeMirror typeArg : declaredType.getTypeArguments()) {
        if (!(typeArg instanceof WildcardType)) {
          errorf(
              param,
              "parameter '%s' has generic type %s, but only wildcard type parameters are"
                  + " allowed. Type inference in a Starlark-exposed method is unsafe. See"
                  + " StarlarkMethod class documentation for details.",
              param.getSimpleName(),
              paramType);
        }
      }
    }

    // Ensure positional arguments are documented.
    if (!paramAnnot.documented() && paramAnnot.positional()) {
      errorf(
          param, "Parameter '%s' must be documented because it is positional.", paramAnnot.name());
    }
  }

  private static boolean hasPlusMinusPrefix(String s) {
    return s.charAt(0) == '-' || s.charAt(0) == '+';
  }

  // Returns the logical type of ParamType.type.
  private static TypeMirror getParamTypeType(ParamType paramType) {
    // See explanation of this hack at Element.getAnnotation
    // and at https://stackoverflow.com/a/10167558.
    try {
      paramType.type();
      throw new IllegalStateException("unreachable");
    } catch (MirroredTypeException ex) {
      return ex.getTypeMirror();
    }
  }

  private void checkSpecialParams(ExecutableElement method, StarlarkMethod annot) {
    List<? extends VariableElement> params = method.getParameters();
    int index = annot.parameters().length;

    // insufficient parameters?
    int special = numExpectedSpecialParams(annot);
    if (index + special > params.size()) {
      errorf(
          method,
          "method %s is annotated with %d Params plus %d special parameters, but has only %d"
              + " parameter variables",
          method.getSimpleName(),
          index,
          special,
          params.size());
      return; // not safe to proceed
    }

    if (!annot.extraPositionals().name().isEmpty()) {
      VariableElement param = params.get(index++);
      // Allow any supertype of Tuple.
      TypeMirror tupleType =
          types.getDeclaredType(elements.getTypeElement("net.starlark.java.eval.Tuple"));
      if (!types.isAssignable(tupleType, param.asType())) {
        errorf(
            param,
            "extraPositionals special parameter '%s' has type %s, to which a Tuple cannot be"
                + " assigned",
            param.getSimpleName(),
            param.asType());
      }
    }

    if (!annot.extraKeywords().name().isEmpty()) {
      VariableElement param = params.get(index++);
      // Allow any supertype of Dict<String, Object>.
      TypeMirror dictOfStringObjectType =
          types.getDeclaredType(
              elements.getTypeElement("net.starlark.java.eval.Dict"),
              getType("java.lang.String"),
              getType("java.lang.Object"));
      if (!types.isAssignable(dictOfStringObjectType, param.asType())) {
        errorf(
            param,
            "extraKeywords special parameter '%s' has type %s, to which Dict<String, Object>"
                + " cannot be assigned",
            param.getSimpleName(),
            param.asType());
      }
    }

    if (annot.useStarlarkThread()) {
      VariableElement param = params.get(index++);
      TypeMirror threadType = getType("net.starlark.java.eval.StarlarkThread");
      if (!types.isSameType(threadType, param.asType())) {
        errorf(
            param,
            "for useStarlarkThread special parameter '%s', got type %s, want StarlarkThread",
            param.getSimpleName(),
            param.asType());
      }
    }

    // surplus parameters?
    if (index < params.size()) {
      errorf(
          params.get(index), // first surplus parameter
          "method %s is annotated with %d Params plus %d special parameters, yet has %d parameter"
              + " variables",
          method.getSimpleName(),
          annot.parameters().length,
          special,
          params.size());
    }
  }

  private static int numExpectedSpecialParams(StarlarkMethod annot) {
    int n = 0;
    n += annot.extraPositionals().name().isEmpty() ? 0 : 1;
    n += annot.extraKeywords().name().isEmpty() ? 0 : 1;
    n += annot.useStarlarkThread() ? 1 : 0;
    return n;
  }

  // Reports a (formatted) error and fails the compilation.
  @FormatMethod
  private void errorf(Element e, String format, Object... args) {
    messager.printMessage(Diagnostic.Kind.ERROR, String.format(format, args), e);
  }
}
