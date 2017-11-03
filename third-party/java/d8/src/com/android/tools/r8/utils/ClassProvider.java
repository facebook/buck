// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.ClassFileResourceProvider;
import com.android.tools.r8.Resource;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.ClassKind;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.JarApplicationReader;
import com.android.tools.r8.graph.JarClassFileReader;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.io.Closer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Represents a provider for classes loaded from different sources. */
public abstract class ClassProvider<T extends DexClass> {
  private final ClassKind classKind;

  ClassProvider(ClassKind classKind) {
    this.classKind = classKind;
  }

  /** The kind of the classes created by the provider. */
  final ClassKind getClassKind() {
    return classKind;
  }

  /**
   * The provider uses the callback to return all the classes that might
   * be associated with the descriptor asked for.
   *
   * NOTE: the provider is not required to cache created classes and this
   * method may create a new class instance in case it is called twice for
   * the same type. For this reason it is recommended that the provider
   * user only calls this method once per any given type.
   *
   * NOTE: thread-safe.
   */
  public abstract void collectClass(DexType type, Consumer<T> classConsumer);

  /**
   * Returns all the types of classes that might be produced by this provider.
   *
   * NOTE: thread-safe.
   */
  public abstract Collection<DexType> collectTypes();

  /** Create class provider for java class resource provider. */
  public static <T extends DexClass> ClassProvider<T> forClassFileResources(
      ClassKind classKind, ClassFileResourceProvider provider, JarApplicationReader reader) {
    return new ClassFileResourceReader<>(classKind, provider, reader);
  }

  /** Create class provider for preloaded classes, classes may have conflicting names. */
  public static <T extends DexClass> ClassProvider<T> forPreloadedClasses(
      ClassKind classKind, Collection<T> classes) {
    ImmutableListMultimap.Builder<DexType, T> builder = ImmutableListMultimap.builder();
    for (T clazz : classes) {
      builder.put(clazz.type, clazz);
    }
    return new PreloadedClassProvider<>(classKind, builder.build());
  }

  /** Create class provider for preloaded classes. */
  public static <T extends DexClass> ClassProvider<T> combine(
      ClassKind classKind, List<ClassProvider<T>> providers) {
    return new CombinedClassProvider<>(classKind, providers);
  }

  private static class ClassFileResourceReader<T extends DexClass> extends ClassProvider<T> {
    private final ClassKind classKind;
    private final ClassFileResourceProvider provider;
    private final JarApplicationReader reader;

    private ClassFileResourceReader(
        ClassKind classKind, ClassFileResourceProvider provider, JarApplicationReader reader) {
      super(classKind);
      this.classKind = classKind;
      this.provider = provider;
      this.reader = reader;
    }

    @Override
    public void collectClass(DexType type, Consumer<T> classConsumer) {
      String descriptor = type.descriptor.toString();
      Resource resource = provider.getResource(descriptor);
      if (resource != null) {
        try (Closer closer = Closer.create()) {
          JarClassFileReader classReader =
              new JarClassFileReader(reader, classKind.bridgeConsumer(classConsumer));
          classReader.read(resource.origin, classKind, closer.register(resource.getStream()));
        } catch (IOException e) {
          throw new CompilationError("Failed to load class: " + descriptor, e);
        }
      }
    }

    @Override
    public Collection<DexType> collectTypes() {
      List<DexType> types = new ArrayList<>();
      for (String descriptor : provider.getClassDescriptors()) {
        types.add(reader.options.itemFactory.createType(descriptor));
      }
      return types;
    }

    @Override
    public String toString() {
      return "class-resource-provider(" + provider.toString() + ")";
    }
  }

  private static class PreloadedClassProvider<T extends DexClass> extends ClassProvider<T> {
    private final Multimap<DexType, T> classes;

    private PreloadedClassProvider(ClassKind classKind, Multimap<DexType, T> classes) {
      super(classKind);
      this.classes = classes;
    }

    @Override
    public void collectClass(DexType type, Consumer<T> classConsumer) {
      for (T clazz : classes.get(type)) {
        classConsumer.accept(clazz);
      }
    }

    @Override
    public Collection<DexType> collectTypes() {
      return classes.keys();
    }

    @Override
    public String toString() {
      return "preloaded(" + classes.size() + ")";
    }
  }

  private static class CombinedClassProvider<T extends DexClass> extends ClassProvider<T> {
    private final List<ClassProvider<T>> providers;

    private CombinedClassProvider(ClassKind classKind, List<ClassProvider<T>> providers) {
      super(classKind);
      this.providers = providers;
    }

    @Override
    public void collectClass(DexType type, Consumer<T> classConsumer) {
      for (ClassProvider<T> provider : providers) {
        provider.collectClass(type, classConsumer);
      }
    }

    @Override
    public Collection<DexType> collectTypes() {
      Set<DexType> types = Sets.newIdentityHashSet();
      for (ClassProvider<T> provider : providers) {
        types.addAll(provider.collectTypes());
      }
      return types;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      String prefix = "combined(";
      for (ClassProvider<T> provider : providers) {
        builder.append(prefix);
        prefix = ", ";
        builder.append(provider);
      }
      return builder.append(")").toString();
    }
  }
}
