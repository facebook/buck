/*
 * Portions Copyright (c) Facebook, Inc. and its affiliates.
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

import java.lang.reflect.Method;
import java.util.Arrays;
import net.starlark.java.annot.FnPurity;
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
  /** Can reuse fastcall positional arguments if parameter count matches. */
  private final MethodDescriptorGenerated generated;

  private final FnPurity purity;
  /** Size of array to be passed to {@link #call(Object, Object[], StarlarkThread)}. */
  private final int argsSize;

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
      MethodDescriptorGenerated generated) {
    this.method = method;
    this.annotation = annotation;
    this.name = name;
    this.doc = doc;
    this.documented = documented;
    this.structField = structField;
    this.parameters =
        method.getDeclaringClass() == StringModule.class
            ? Arrays.copyOfRange(parameters, 1, parameters.length)
            : parameters;
    this.extraPositionals = extraPositionals;
    this.extraKeywords = extraKeywords;
    this.selfCall = selfCall;
    this.allowReturnNones = allowReturnNones;
    this.useStarlarkThread = useStarlarkThread;
    this.generated = generated;
    this.purity = annotation.purity();

    this.argsSize = parameters.length + (extraPositionals ? 1 : 0) + (extraKeywords ? 1 : 0);
  }

  /** Returns the StarlarkMethod annotation corresponding to this method. */
  StarlarkMethod getAnnotation() {
    return annotation;
  }

  /** @return Starlark method descriptor for provided Java method and signature annotation. */
  static MethodDescriptor of(
      Method method, StarlarkMethod annotation, MethodDescriptorGenerated generated) {

    Class<?>[] paramClasses = method.getParameterTypes();
    Param[] paramAnnots = annotation.parameters();
    ParamDescriptor[] params = new ParamDescriptor[paramAnnots.length];
    Arrays.setAll(params, i -> ParamDescriptor.of(paramAnnots[i], paramClasses[i]));

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
        generated);
  }

  /** Calls this method, which must have {@code structField=true}. */
  Object callField(Object obj, StarlarkSemantics semantics, StarlarkThread thread)
      throws EvalException, InterruptedException {
    if (!structField) {
      throw new IllegalStateException("not a struct field: " + name);
    }
    try {
      return call(obj, ArraysForStarlark.EMPTY_OBJECT_ARRAY, thread);
    } catch (MethodDescriptorGenerated.ArgumentBindException e) {
      throw new RuntimeException("not possible, because there are no arguments", e);
    }
  }

  /**
   * Call description with positional-only argument. Generated code will perform correctnes check.
   */
  Object callPos(Object obj, Object[] args, StarlarkThread thread)
      throws EvalException, InterruptedException, MethodDescriptorGenerated.ArgumentBindException {
    try {
      return generated.invokePos(obj, args, thread);
    } catch (MethodDescriptorGenerated.ArgumentBindException e) {
      // handled by the `BuiltinFunction`
      throw e;
    } catch (EvalException | InterruptedException | RuntimeException | Error e) {
      // Don't intercept unchecked exceptions.
      throw e;
    } catch (Exception e) {
      // All other checked exceptions (e.g. LabelSyntaxException) are reported to Starlark.
      throw new EvalException(e);
    }
  }

  /**
   * Invokes this method using {@code obj} as a target and {@code args} as Java arguments.
   *
   * <p>Methods with {@code void} return type return {@code None} following Python convention.
   *
   * <p>The Mutability is used if it is necessary to allocate a Starlark copy of a Java result.
   */
  Object call(Object obj, Object[] args, StarlarkThread thread)
      throws EvalException, InterruptedException, MethodDescriptorGenerated.ArgumentBindException {
    try {
      return generated.invoke(obj, args, thread);
    } catch (MethodDescriptorGenerated.ArgumentBindException e) {
      // handled by the `BuiltinFunction`
      throw e;
    } catch (EvalException | InterruptedException | RuntimeException | Error e) {
      // Don't intercept unchecked exceptions.
      throw e;
    } catch (Exception e) {
      // All other checked exceptions (e.g. LabelSyntaxException) are reported to Starlark.
      throw new EvalException(e);
    }
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

  /** Size of array passable to {@link #call(Object, Object[], StarlarkThread)}. */
  int getArgsSize() {
    return argsSize;
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

  public FnPurity getPurity() {
    return purity;
  }
}
