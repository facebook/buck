/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java.abi.source;

import com.facebook.buck.util.liteinfersupport.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.AnnotatedConstruct;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.AbstractAnnotationValueVisitor8;

/**
 * Support for @{link TreeBackedAnnotatedConstruct#getAnnotation}.
 *
 * <p>{@code getAnnotation} can throw unexpected exceptions when building source-only ABIs, due to
 * some of the classes it tries to load being unavailable. We try to return our own implementations
 * of the annotations instead, bypassing javac's support altogether. However, if the annotation
 * processor requests a field that we can't fake, we have to fall back to the underlying annotation.
 */
public class TreeBackedAnnotationFactory {
  public static <A extends Annotation> A createOrGetUnderlying(
      TreeBackedAnnotatedConstruct annotatedConstruct,
      AnnotatedConstruct underlyingConstruct,
      Class<A> annotationType) {
    for (AnnotationMirror mirror : underlyingConstruct.getAnnotationMirrors()) {
      if (mirror.getAnnotationType().toString().equals(annotationType.getName()))
        return annotationType.cast(
            Proxy.newProxyInstance(
                annotationType.getClassLoader(),
                new Class[] {annotationType},
                new Handler(annotatedConstruct, annotationType, mirror)));
    }
    // Either the annotation doesn't exist or name comparison failed in some weird way.
    // Either way, fall back to the underlying construct.
    return annotatedConstruct.getAnnotationWithBetterErrors(annotationType);
  }

  static class Handler implements InvocationHandler {
    /** Used to get the fallback if we can't satisfy a request. */
    private final TreeBackedAnnotatedConstruct annotatedConstruct;
    /** Type of this annotation, which the caller can request. */
    private final Class<? extends Annotation> annotationType;
    /** Safe "bag of values" object that we use to supply values whenever possible. */
    private final AnnotationMirror mirror;
    /** Cached underlying annotation from {@code underlyingConstruct}, or null if not set yet. */
    @Nullable Annotation annotationCache;
    /** Cached values from {@code mirror}. */
    @Nullable private Map<String, AnnotationValue> valueCache = null;

    public Handler(
        TreeBackedAnnotatedConstruct annotatedConstruct,
        Class<? extends Annotation> annotationType,
        AnnotationMirror mirror) {
      this.annotatedConstruct = annotatedConstruct;
      this.annotationType = annotationType;
      this.mirror = mirror;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (method.getName().equals("annotationType")) {
        return annotationType;
      }

      if (method.getName().equals("equals")) {
        // Fall back to comparing the actual annotations.
        populateAnnotation();
        if (!Proxy.isProxyClass(args[0].getClass())) {
          return false;
        }
        InvocationHandler rawHandler = Proxy.getInvocationHandler(args[0]);
        if (!(rawHandler instanceof Handler)) {
          return false;
        }
        Handler other = (Handler) rawHandler;
        other.populateAnnotation();
        return annotationCache.equals(other.annotationCache);
      }

      populateValues();
      if (!valueCache.containsKey(method.getName())) {
        throw new IllegalArgumentException("Unrecognized method: " + method.getName());
      }
      AnnotationValue value = valueCache.get(method.getName());
      Object rawValue = new ValueVisitor(method.getReturnType()).visit(value);
      if (rawValue != null) {
        return rawValue;
      }

      // Fall back to trying the annotation.
      populateAnnotation();
      try {
        return method.invoke(annotationCache, args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }

    private void populateAnnotation() {
      annotationCache = annotatedConstruct.getAnnotationWithBetterErrors(annotationType);
    }

    private synchronized void populateValues() {
      if (valueCache == null) {
        valueCache = new HashMap<>();
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
            TreeBackedElements.getElementValuesWithDefaultsStatic(mirror).entrySet()) {
          valueCache.put(entry.getKey().getSimpleName().toString(), entry.getValue());
        }
      }
    }
  }

  /**
   * Validate the type of the annotation value and return it.
   *
   * <p>Returns null if we don't/can't support it.
   */
  static class ValueVisitor extends AbstractAnnotationValueVisitor8<Object, Void> {
    private final Class<?> type;

    ValueVisitor(Class<?> type) {
      this.type = type;
    }

    @Override
    public Object visitBoolean(boolean b, Void aVoid) {
      if (!type.equals(Boolean.TYPE)) {
        throw new IllegalArgumentException("Wrong type.");
      }
      return b;
    }

    @Override
    public Object visitByte(byte b, Void aVoid) {
      if (!type.equals(Byte.TYPE)) {
        throw new IllegalArgumentException("Wrong type.");
      }
      return b;
    }

    @Override
    public Object visitChar(char c, Void aVoid) {
      if (!type.equals(Character.TYPE)) {
        throw new IllegalArgumentException("Wrong type.");
      }
      return c;
    }

    @Override
    public Object visitDouble(double d, Void aVoid) {
      if (!type.equals(Double.TYPE)) {
        throw new IllegalArgumentException("Wrong type.");
      }
      return d;
    }

    @Override
    public Object visitFloat(float f, Void aVoid) {
      if (!type.equals(Float.TYPE)) {
        throw new IllegalArgumentException("Wrong type.");
      }
      return f;
    }

    @Override
    public Object visitInt(int i, Void aVoid) {
      if (!type.equals(Integer.TYPE)) {
        throw new IllegalArgumentException("Wrong type.");
      }
      return i;
    }

    @Override
    public Object visitLong(long i, Void aVoid) {
      if (!type.equals(Long.TYPE)) {
        throw new IllegalArgumentException("Wrong type.");
      }
      return i;
    }

    @Override
    public Object visitShort(short s, Void aVoid) {
      if (!type.equals(Short.TYPE)) {
        throw new IllegalArgumentException("Wrong type.");
      }
      return s;
    }

    @Override
    @Nullable
    public Object visitString(String s, Void aVoid) {
      if (type.equals(Class.class)) {
        // Error types call visitString for some reason.
        return null;
      }

      if (!type.equals(String.class)) {
        throw new IllegalArgumentException("Wrong type.");
      }
      return s;
    }

    @Override
    @Nullable
    public Object visitType(TypeMirror t, Void aVoid) {
      return null;
    }

    @Override
    @Nullable
    public Object visitEnumConstant(VariableElement c, Void aVoid) {
      return null;
    }

    @Override
    @Nullable
    public Object visitAnnotation(AnnotationMirror a, Void aVoid) {
      return null;
    }

    @Override
    @Nullable
    public Object visitArray(List<? extends AnnotationValue> vals, Void aVoid) {
      // TODO: Maybe support this for primitives and strings?
      return null;
    }
  }
}
