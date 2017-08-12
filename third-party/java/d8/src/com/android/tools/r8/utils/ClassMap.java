// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.ClassKind;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexType;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Represents a collection of classes. Collection can be fully loaded,
 * lazy loaded or have preloaded classes along with lazy loaded content.
 */
public abstract class ClassMap<T extends DexClass> {
  // For each type which has ever been queried stores one class loaded from
  // resources provided by different resource providers.
  //
  // NOTE: all access must be synchronized on `classes`.
  private final Map<DexType, Supplier<T>> classes;

  // Class provider if available.
  //
  // If the class provider is `null` it indicates that all classes are already present
  // in a map referenced by `classes` and thus the collection is fully loaded.
  //
  // NOTE: all access must be synchronized on `classes`.
  private ClassProvider<T> classProvider;

  ClassMap(Map<DexType, Supplier<T>> classes, ClassProvider<T> classProvider) {
    this.classes = classes == null ? new IdentityHashMap<>() : classes;
    this.classProvider = classProvider;
    assert this.classProvider == null || this.classProvider.getClassKind() == getClassKind();
  }

  /** Resolves a class conflict by selecting a class, may generate compilation error. */
  abstract T resolveClassConflict(T a, T b);

  /** Return supplier for preloaded class. */
  abstract Supplier<T> getTransparentSupplier(T clazz);

  /** Kind of the classes supported by this collection. */
  abstract ClassKind getClassKind();

  @Override
  public String toString() {
    synchronized (classes) {
      return classes.size() + " loaded, provider: " +
          (classProvider == null ? "none" : classProvider.toString());
    }
  }

  /** Returns a definition for a class or `null` if there is no such class in the collection. */
  public T get(DexType type) {
    Supplier<T> supplier;

    synchronized (classes) {
      supplier = classes.get(type);

      // Get class supplier, create it if it does not
      // exist and the collection is NOT fully loaded.
      if (supplier == null) {
        if (classProvider == null) {
          // There is no supplier, but the collection is fully loaded.
          return null;
        }

        supplier = new ConcurrentClassLoader<>(this, this.classProvider, type);
        classes.put(type, supplier);
      }
    }

    return supplier.get();
  }

  /** Returns all classes from the collection. The collection must be force-loaded. */
  public List<T> getAllClasses() {
    List<T> loadedClasses = new ArrayList<>();
    synchronized (classes) {
      if (classProvider != null) {
        throw new Unreachable("Getting all classes from not fully loaded collection.");
      }
      for (Supplier<T> supplier : classes.values()) {
        // Since the class map is fully loaded, all suppliers must be
        // loaded and non-null.
        T clazz = supplier.get();
        assert clazz != null;
        loadedClasses.add(clazz);
      }
    }
    return loadedClasses;
  }

  public Iterable<DexType> getAllTypes() {
    return classes.keySet();
  }

  /**
   * Forces loading of all the classes satisfying the criteria specified.
   *
   * NOTE: after this method finishes, the class map is considered to be fully-loaded
   * and thus sealed. This has one side-effect: if we filter out some of the classes
   * with `load` predicate, these classes will never be loaded.
   */
  public void forceLoad(Predicate<DexType> load) {
    Set<DexType> knownClasses;
    ClassProvider<T> classProvider;

    synchronized (classes) {
      classProvider = this.classProvider;
      if (classProvider == null) {
        return;
      }

      // Collects the types which might be represented in fully loaded class map.
      knownClasses = Sets.newIdentityHashSet();
      knownClasses.addAll(classes.keySet());
    }

    // Add all types the class provider provides. Note that it may take time for class
    // provider to collect these types, so ve do it outside synchronized context.
    knownClasses.addAll(classProvider.collectTypes());

    // Make sure all the types in `knownClasses` are loaded.
    //
    // We just go and touch every class, thus triggering their loading if they
    // are not loaded so far. In case the class has already been loaded,
    // touching the class will be a no-op with minimal overhead.
    for (DexType type : knownClasses) {
      if (load.test(type)) {
        get(type);
      }
    }

    synchronized (classes) {
      if (this.classProvider == null) {
        return; // Has been force-loaded concurrently.
      }

      // We avoid calling get() on a class supplier unless we know it was loaded.
      // At this time `classes` may have more types then `knownClasses`, but for
      // all extra classes we expect the supplier to return 'null' after loading.
      Iterator<Map.Entry<DexType, Supplier<T>>> iterator = classes.entrySet().iterator();
      while (iterator.hasNext()) {
        Map.Entry<DexType, Supplier<T>> e = iterator.next();

        if (knownClasses.contains(e.getKey())) {
          // Get the class (it is expected to be loaded by this time).
          T clazz = e.getValue().get();
          if (clazz != null) {
            // Since the class is already loaded, get rid of possible wrapping suppliers.
            assert clazz.type == e.getKey();
            e.setValue(getTransparentSupplier(clazz));
            continue;
          }
        }

        // If the type is not in `knownClasses` or resolves to `null`,
        // just remove the record from the map.
        iterator.remove();
      }

      // Mark the class map as fully loaded.
      this.classProvider = null;
    }
  }

  // Supplier implementing a thread-safe loader for a class loaded from a
  // class provider. Helps avoid synchronizing on the whole class map
  // when loading a class.
  private static class ConcurrentClassLoader<T extends DexClass> implements Supplier<T> {
    private ClassMap<T> classMap;
    private ClassProvider<T> provider;
    private DexType type;

    private T clazz = null;
    private volatile boolean ready = false;

    ConcurrentClassLoader(ClassMap<T> classMap, ClassProvider<T> provider, DexType type) {
      this.classMap = classMap;
      this.provider = provider;
      this.type = type;
    }

    @Override
    public T get() {
      if (ready) {
        return clazz;
      }

      synchronized (this) {
        if (!ready) {
          assert classMap != null && provider != null && type != null;
          provider.collectClass(type, createdClass -> {
            assert createdClass != null;
            assert classMap.getClassKind().isOfKind(createdClass);
            assert !ready;

            if (createdClass.type != type) {
              throw new CompilationError(
                  "Class content provided for type descriptor " + type.toSourceString() +
                      " actually defines class " + createdClass.type.toSourceString());
            }

            if (clazz == null) {
              clazz = createdClass;
            } else {
              // The class resolution *may* generate a compilation error as one of
              // possible resolutions. In this case we leave `value` in (false, null)
              // state so in rare case of another thread trying to get the same class
              // before this error is propagated it will get the same conflict.
              T oldClass = clazz;
              clazz = null;
              clazz = classMap.resolveClassConflict(oldClass, createdClass);
            }
          });

          classMap = null;
          provider = null;
          type = null;
          ready = true;
        }
      }

      assert ready;
      assert classMap == null && provider == null && type == null;
      return clazz;
    }
  }
}
