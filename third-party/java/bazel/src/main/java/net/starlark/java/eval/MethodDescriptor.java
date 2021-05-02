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

package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.errorprone.annotations.CheckReturnValue;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkMethod;

/**
 * A value class to store Methods with their corresponding {@link StarlarkMethod} annotation
 * metadata. This is needed because the annotation is sometimes in a superclass.
 *
 * <p>The annotation metadata is duplicated in this class to avoid usage of Java dynamic proxies
 * which are ~7× slower.
 */
final class MethodDescriptor {
  private final Method method;
  private final StarlarkMethod annotation;

  private final String name;
  private final String doc;
  private final boolean documented;
  private final boolean structField;
  private final ParamDescriptor[] parameters;
  private final boolean extraPositionals;
  private final boolean extraKeywords;
  private final boolean selfCall;
  private final boolean allowReturnNones;
  private final boolean useStarlarkThread;
  private final boolean useStarlarkSemantics;
  /** Can reuse fastcall positional arguments if parameter count matches. */
  private final boolean canReusePositionalWithoutChecks;
  private final boolean speculativeSafe;
  private final MethodDescriptorGenerated generated;

  private MethodDescriptor(
      Method method,
      StarlarkMethod annotation,
      String name,
      String doc,
      boolean documented,
      boolean structField,
      ParamDescriptor[] parameters,
      boolean extraPositionals,
      boolean extraKeywords,
      boolean selfCall,
      boolean allowReturnNones,
      boolean trustReturnsValid,
      boolean useStarlarkThread,
      boolean useStarlarkSemantics,
      MethodDescriptorGenerated generated) {
    this.method = method;
    this.annotation = annotation;
    this.name = name;
    this.doc = doc;
    this.documented = documented;
    this.structField = structField;
    this.parameters = method.getDeclaringClass() == StringModule.class ? Arrays.copyOfRange(parameters, 1, parameters.length) : parameters;
    this.extraPositionals = extraPositionals;
    this.extraKeywords = extraKeywords;
    this.selfCall = selfCall;
    this.allowReturnNones = allowReturnNones;
    this.useStarlarkThread = useStarlarkThread;
    this.useStarlarkSemantics = useStarlarkSemantics;
    this.speculativeSafe = annotation.speculativeSafe();
    this.generated = generated;

    if (extraKeywords || extraPositionals || useStarlarkSemantics) {
      this.canReusePositionalWithoutChecks = false;
    } else if (!Arrays.stream(parameters).allMatch(MethodDescriptor::paramCanBeUsedAsPositionalWithoutChecks)) {
      this.canReusePositionalWithoutChecks = false;
    } else {
      this.canReusePositionalWithoutChecks = true;
    }
  }

  private static boolean paramCanBeUsedAsPositionalWithoutChecks(ParamDescriptor param) {
    return param.isPositional() && param.disabledByFlag() == null;
  }

  /** Returns the StarlarkMethod annotation corresponding to this method. */
  StarlarkMethod getAnnotation() {
    return annotation;
  }

  /** @return Starlark method descriptor for provided Java method and signature annotation. */
  static MethodDescriptor of(
      Method method, StarlarkMethod annotation, StarlarkSemantics semantics,
      MethodDescriptorGenerated generated) {
    // This happens when the interface is public but the implementation classes
    // have reduced visibility.
    method.setAccessible(true);

    Class<?>[] paramClasses = method.getParameterTypes();
    Param[] paramAnnots = annotation.parameters();
    ParamDescriptor[] params = new ParamDescriptor[paramAnnots.length];
    Arrays.setAll(params, i -> ParamDescriptor.of(paramAnnots[i], paramClasses[i], semantics));

    return new MethodDescriptor(
        method,
        annotation,
        annotation.name(),
        annotation.doc(),
        annotation.documented(),
        annotation.structField(),
        params,
        !annotation.extraPositionals().name().isEmpty(),
        !annotation.extraKeywords().name().isEmpty(),
        annotation.selfCall(),
        annotation.allowReturnNones(),
        annotation.trustReturnsValid(),
        annotation.useStarlarkThread(),
        annotation.useStarlarkSemantics(),
        generated);
  }

  /** Calls this method, which must have {@code structField=true}. */
  Object callField(Object obj, StarlarkSemantics semantics, StarlarkThread thread)
      throws EvalException, InterruptedException {
    if (!structField) {
      throw new IllegalStateException("not a struct field: " + name);
    }
    Object[] args = useStarlarkSemantics ? new Object[] {semantics} : ArraysForStarlark.EMPTY_OBJECT_ARRAY;
    return call(obj, args, thread);
  }

  /**
   * Invokes this method using {@code obj} as a target and {@code args} as Java arguments.
   *
   * <p>Methods with {@code void} return type return {@code None} following Python convention.
   *
   * <p>The Mutability is used if it is necessary to allocate a Starlark copy of a Java result.
   */
  Object call(Object obj, Object[] args, StarlarkThread thread)
      throws EvalException, InterruptedException {
    Preconditions.checkNotNull(obj);
    try {
      return generated.invoke(obj, args, thread);
    } catch (EvalException | InterruptedException | RuntimeException | Error e) {
      // Don't intercept unchecked exceptions.
      throw e;
    } catch (Exception e) {
      // All other checked exceptions (e.g. LabelSyntaxException) are reported to Starlark.
      throw new EvalException(e);
    }
  }

  @CheckReturnValue // don't forget to throw it
  private NullPointerException methodInvocationReturnedNull(Object[] args) {
    return new NullPointerException(
        "method invocation returned null: " + getName() + Tuple.of(args));
  }

  /** @see StarlarkMethod#name() */
  String getName() {
    return name;
  }

  Method getMethod() {
    return method;
  }

  /** @see StarlarkMethod#structField() */
  boolean isStructField() {
    return structField;
  }

  /** @see StarlarkMethod#useStarlarkThread() */
  boolean isUseStarlarkThread() {
    return useStarlarkThread;
  }

  /** @see StarlarkMethod#useStarlarkSemantics() */
  boolean isUseStarlarkSemantics() {
    return useStarlarkSemantics;
  }

  /** @return {@code true} if this method accepts extra arguments ({@code *args}) */
  boolean acceptsExtraArgs() {
    return extraPositionals;
  }

  /** @see StarlarkMethod#extraKeywords() */
  boolean acceptsExtraKwargs() {
    return extraKeywords;
  }

  /** @see StarlarkMethod#parameters() */
  ParamDescriptor[] getParameters() {
    return parameters;
  }

  /** Returns the index of the named parameter or -1 if not found. */
  int getParameterIndex(String name) {
    for (int i = 0; i < parameters.length; i++) {
      if (parameters[i].getName().equals(name)) {
        return i;
      }
    }
    return -1;
  }

  /** @see StarlarkMethod#documented() */
  boolean isDocumented() {
    return documented;
  }

  /** @see StarlarkMethod#doc() */
  String getDoc() {
    return doc;
  }

  /** @see StarlarkMethod#selfCall() */
  boolean isSelfCall() {
    return selfCall;
  }

  public boolean isSpeculativeSafe() {
    return speculativeSafe;
  }

  /** Descriptor behavior depends on semantics. */
  boolean isSemanticsDependent() {
    // Note `disableWithFlag` and `enableOnlyWithFlag` are not used anywhere
    // in method descriptor itself, but these are used to determine
    // whether include or exclude method in the list of object methods.
    return !annotation.disableWithFlag().isEmpty()
        || !annotation.enableOnlyWithFlag().isEmpty()
        || Arrays.stream(parameters).anyMatch(ParamDescriptor::isSemanticsDependent);
  }

  boolean isCanReusePositionalWithoutChecks() {
    return canReusePositionalWithoutChecks;
  }
}
