// Copyright 2014 The Bazel Authors. All rights reserved.
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
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkAnnotations;
import net.starlark.java.annot.StarlarkGeneratedFiles;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.annot.processor.StarlarkMethodProcessor;
import net.starlark.java.syntax.StarlarkStringInterner;

/** Helper functions for StarlarkMethod-annotated fields and methods. */
final class CallUtils {

  private CallUtils() {} // uninstantiable

  private static CacheValue getCacheValue(Class<?> cls, StarlarkSemantics semantics) {
    if (cls == String.class) {
      cls = StringModule.class;
    }
    Key key = new Key(cls, semantics);

    // Avoid computeIfAbsent! It is not reentrant,
    // and if getCacheValue is called before Starlark.UNIVERSE
    // is initialized then the computation will re-enter the cache.
    // (This is less likely now that CallUtils is private.)
    // See b/161479826 for history.
    //
    // Concurrent calls may result in duplicate computation.
    // If this is a performance concern, then we should use a CHM
    // of futures (see ch.9 of gopl.io) so that the computation
    // is not done in the critical section of the map stripe.
    CacheValue v = cache.get(key);
    if (v == null) {
      v = buildCacheValue(key);
      CacheValue prev = cache.putIfAbsent(key, v);
      if (prev != null) {
        v = prev; // first thread wins
      }
    }
    return v;
  }

  // Key is a simple Pair<Class, StarlarkSemantics>.
  private static final class Key {
    final Class<?> cls;
    final StarlarkSemantics semantics;

    Key(Class<?> cls, StarlarkSemantics semantics) {
      this.cls = cls;
      this.semantics = semantics;
    }

    @Override
    public boolean equals(Object that) {
      return this == that
          || (that instanceof Key
              && this.cls.equals(((Key) that).cls)
              && this.semantics.equals(((Key) that).semantics));
    }

    @Override
    public int hashCode() {
      return 31 * cls.hashCode() + semantics.hashCode();
    }
  }

  // Information derived from a StarlarkMethod-annotated class and a StarlarkSemantics.
  private static class CacheValue {
    @Nullable MethodDescriptor selfCall;
    // All StarlarkMethod-annotated Java methods, sans selfCall, sorted by Java method name.
    ImmutableMap<String, MethodDescriptor> methods;
    // Subset of CacheValue.methods for which structField=True, sorted by Java method name.
    ImmutableMap<String, MethodDescriptor> fields;
  }

  // A cache of information derived from a StarlarkMethod-annotated class and a StarlarkSemantics.
  private static final ConcurrentHashMap<Key, CacheValue> cache = new ConcurrentHashMap<>();

  private static CacheValue buildCacheValue(Key key) {
    MethodDescriptor selfCall = null;
    ImmutableMap.Builder<String, MethodDescriptor> methods = ImmutableMap.builder();
    Map<String, MethodDescriptor> fields = new HashMap<>();

    GeneratedDescriptors generatedDescriptors = null;

    // Sort methods by Java name, for determinism.
    Method[] classMethods = key.cls.getMethods();
    Arrays.sort(classMethods, Comparator.comparing(Method::getName));
    for (Method method : classMethods) {
      // Synthetic methods lead to false multiple matches
      if (method.isSynthetic()) {
        continue;
      }

      // annotated?
      StarlarkMethod callable = StarlarkAnnotations.getStarlarkMethod(method);
      if (callable == null) {
        continue;
      }

      // enabled by semantics?
      if (!key.semantics.isFeatureEnabledBasedOnTogglingFlags(
          callable.enableOnlyWithFlag(), callable.disableWithFlag())) {
        continue;
      }

      if (generatedDescriptors == null) {
        generatedDescriptors = generatedDescriptors(key.cls);
      }

      MethodDescriptorGenerated generated = generatedDescriptors.descriptors.get(method.getName());
      Preconditions.checkState(
          generated != null,
          "descriptor for method %s not found among descriptors of %s",
          method,
          key.cls);

      MethodDescriptor descriptor = MethodDescriptor.of(method, callable, key.semantics, generated);

      // self-call method?
      if (callable.selfCall()) {
        if (selfCall != null) {
          throw new IllegalArgumentException(
              String.format("Class %s has two selfCall methods defined", key.cls.getName()));
        }
        selfCall = descriptor;
        continue;
      }

      // Identifiers are interned in the parser, intern them here too
      // to speed up lookup in dot expressions.
      String name = StarlarkStringInterner.intern(callable.name());

      // regular method
      methods.put(name, descriptor);

      // field method?
      if (descriptor.isStructField() && fields.put(name, descriptor) != null) {
        // TODO(b/72113542): Validate with annotation processor instead of at runtime.
        throw new IllegalArgumentException(
            String.format(
                "Class %s declares two structField methods named %s",
                key.cls.getName(), callable.name()));
      }
    }

    CacheValue value = new CacheValue();
    value.selfCall = selfCall;
    value.methods = methods.build();
    value.fields = ImmutableMap.copyOf(fields);
    return value;
  }

  private static class GeneratedDescriptors {
    private final ImmutableMap<String, MethodDescriptorGenerated> descriptors;

    private GeneratedDescriptors(
        ImmutableMap<String, MethodDescriptorGenerated> descriptors) {
      this.descriptors = descriptors;
    }
  }

  private static GeneratedDescriptors generatedDescriptors(Class<?> declaringClass) {
    Verify.verify(declaringClass.getName().startsWith(declaringClass.getPackage().getName()));

    ImmutableMap.Builder<String, MethodDescriptorGenerated> descriptors = ImmutableMap.builder();

    findHandlerClasses(declaringClass, descriptors, new HashSet<>());

    return new GeneratedDescriptors(descriptors.build());
  }

  private static void findHandlerClasses(
      Class<?> current,
      ImmutableMap.Builder<String, MethodDescriptorGenerated> descriptors,
      HashSet<Class<?>> visitedClasses) {
    if (current == Object.class) {
      return;
    }

    if (!visitedClasses.add(current)) {
      return;
    }

    String packageRelativeName = current.getName().substring(current.getPackage().getName().length() + 1);
    String generatedFqn = current.getPackage().getName()
        + "."
        + packageRelativeName.replace("$", "_")
        + StarlarkGeneratedFiles.GENERATED_CLASS_NAME_SUFFIX;
    if (current.getClassLoader() == null) {
      if (current.getName().startsWith("java.")) {
        return;
      }
      throw new RuntimeException("class has no classloader: " + current);
    }
    try {
      Class<?> builtinsClass = current.getClassLoader().loadClass(generatedFqn);
      try {
        MethodDescriptorGenerated[] handlers = (MethodDescriptorGenerated[]) builtinsClass.getField("HANDLERS").get(null);
        for (MethodDescriptorGenerated handler : handlers) {
          descriptors.put(handler.javaMethodName, handler);
        }
      } catch (NoSuchFieldException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    } catch (ClassNotFoundException e) {
      // ignore
    }

    if (current.getSuperclass() != null) {
      findHandlerClasses(current.getSuperclass(), descriptors, visitedClasses);
    }
    for (Class<?> intf : current.getInterfaces()) {
      findHandlerClasses(intf, descriptors, visitedClasses);
    }
  }

  /**
   * Returns the set of all StarlarkMethod-annotated Java methods (excluding the self-call method)
   * of the specified class.
   */
  static ImmutableMap<String, MethodDescriptor> getAnnotatedMethods(
      StarlarkSemantics semantics, Class<?> objClass) {
    return getCacheValue(objClass, semantics).methods;
  }

  /**
   * Returns the value of the Starlark field of {@code x}, implemented by a Java method with a
   * {@code StarlarkMethod(structField=true)} annotation.
   */
  static Object getAnnotatedField(StarlarkSemantics semantics, Object x, String fieldName)
      throws EvalException, InterruptedException {
    MethodDescriptor desc = getCacheValue(x.getClass(), semantics).fields.get(fieldName);
    if (desc == null) {
      throw Starlark.errorf("value of type %s has no .%s field", Starlark.type(x), fieldName);
    }
    return desc.callField(x, semantics, /*mu=*/ null);
  }

  /** Returns the names of the Starlark fields of {@code x} under the specified semantics. */
  static ImmutableSet<String> getAnnotatedFieldNames(StarlarkSemantics semantics, Object x) {
    return getCacheValue(x.getClass(), semantics).fields.keySet();
  }

  /**
   * Returns a {@link MethodDescriptor} object representing a function which calls the selfCall java
   * method of the given object (the {@link StarlarkMethod} method with {@link
   * StarlarkMethod#selfCall()} set to true). Returns null if no such method exists.
   */
  @Nullable
  static MethodDescriptor getSelfCallMethodDescriptor(
      StarlarkSemantics semantics, Class<?> objClass) {
    return getCacheValue(objClass, semantics).selfCall;
  }

  /**
   * Returns a {@code selfCall=true} method for the given class under the given Starlark semantics,
   * or null if no such method exists.
   */
  @Nullable
  static Method getSelfCallMethod(StarlarkSemantics semantics, Class<?> objClass) {
    MethodDescriptor descriptor = getCacheValue(objClass, semantics).selfCall;
    if (descriptor == null) {
      return null;
    }
    return descriptor.getMethod();
  }
}
