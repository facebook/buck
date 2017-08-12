// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.ir.code.Invoke.Type;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

public class AppInfo {

  public final DexApplication app;
  public final DexItemFactory dexItemFactory;
  private final ConcurrentHashMap<DexType, Map<Descriptor<?,?>, KeyedDexItem<?>>> definitions =
      new ConcurrentHashMap<>();

  public AppInfo(DexApplication application) {
    this.app = application;
    this.dexItemFactory = app.dexItemFactory;
  }

  protected AppInfo(AppInfo previous) {
    this.app = previous.app;
    this.dexItemFactory = app.dexItemFactory;
    this.definitions.putAll(previous.definitions);
  }

  protected AppInfo(DirectMappedDexApplication application, GraphLense lense) {
    // Rebuild information from scratch, as the application object has changed. We do not
    // use the lense here, as it is about applied occurrences and not definitions.
    // In particular, we have to invalidate the definitions cache, as its keys are no longer
    // valid.
    this(application);
  }

  private Map<Descriptor<?,?>, KeyedDexItem<?>> computeDefinitions(DexType type) {
    Builder<Descriptor<?,?>, KeyedDexItem<?>> builder = ImmutableMap.builder();
    DexClass clazz = app.definitionFor(type);
    if (clazz != null) {
      clazz.forEachMethod(method -> builder.put(method.getKey(), method));
      clazz.forEachField(field -> builder.put(field.getKey(), field));
    }
    return builder.build();
  }

  public Iterable<DexProgramClass> classes() {
    return app.classes();
  }

  public DexClass definitionFor(DexType type) {
    return app.definitionFor(type);
  }

  public DexEncodedMethod definitionFor(DexMethod method) {
    return (DexEncodedMethod) getDefinitions(method.getHolder()).get(method);
  }

  public DexEncodedField definitionFor(DexField field) {
    return (DexEncodedField) getDefinitions(field.getHolder()).get(field);
  }

  private Map<Descriptor<?,?>, KeyedDexItem<?>> getDefinitions(DexType type) {
    Map<Descriptor<?,?>, KeyedDexItem<?>> typeDefinitions = definitions.get(type);
    if (typeDefinitions != null) {
      return typeDefinitions;
    }

    typeDefinitions = computeDefinitions(type);
    Map<Descriptor<?,?>, KeyedDexItem<?>> existing = definitions.putIfAbsent(type, typeDefinitions);
    return existing != null ? existing : typeDefinitions;
  }

  private DexEncodedMethod lookupDirectStaticOrConstructorTarget(DexMethod method) {
    assert method.holder.isClassType();
    return lookupTargetAlongSuperChain(method.holder, method, DexClass::findDirectTarget);
  }

  /**
   * Lookup static method following the super chain from the holder of {@code method}.
   * <p>
   * This method will lookup only static methods.
   *
   * @param method the method to lookup
   * @return The actual target for {@code method} or {@code null} if none found.
   */
  public DexEncodedMethod lookupStaticTarget(DexMethod method) {
    DexEncodedMethod target = lookupDirectStaticOrConstructorTarget(method);
    return target == null || target.accessFlags.isStatic() ? target : null;
  }

  /**
   * Lookup direct method following the super chain from the holder of {@code method}.
   * <p>
   * This method will lookup private and constructor methods.
   *
   * @param method the method to lookup
   * @return The actual target for {@code method} or {@code null} if none found.
   */
  public DexEncodedMethod lookupDirectTarget(DexMethod method) {
    DexEncodedMethod target = lookupDirectStaticOrConstructorTarget(method);
    return target == null || !target.accessFlags.isStatic() ? target : null;
  }

  /**
   * Lookup virtual method starting in type and following the super chain.
   * <p>
   * If the target cannot be found along the super-chain, look for a default implementation in one
   * of the interfaces.
   */
  public DexEncodedMethod lookupVirtualTarget(DexType type, DexMethod method) {
    assert type.isClassType();
    DexEncodedMethod result
        = lookupTargetAlongSuperChain(type, method, DexClass::findVirtualTarget);
    if (result != null) {
      return result;
    }
    return lookupTargetAlongInterfaceChain(type, method,
        (dexClass, dexMethod) -> {
          DexEncodedMethod virtualTarget = dexClass.findVirtualTarget(dexMethod);
          return virtualTarget != null && virtualTarget.getCode() != null
              ? virtualTarget
              : null;
        });
  }

  /**
   * Lookup virtual method starting in type and following the super chain.
   * <p>
   * If the target cannot be found along the super-chain, look for a definition in one of
   * the interfaces.
   */
  public DexEncodedMethod lookupVirtualDefinition(DexType type, DexMethod method) {
    assert type.isClassType();
    DexEncodedMethod result
        = lookupTargetAlongSuperChain(type, method, DexClass::findVirtualTarget);
    if (result != null) {
      return result;
    }
    return lookupTargetAlongInterfaceChain(type, method, DexClass::findVirtualTarget);
  }

  /**
   * Lookup instance field starting in type and following the super chain.
   */
  public DexEncodedField lookupInstanceTarget(DexType type, DexField field) {
    assert type.isClassType();
    return lookupTargetAlongSuperChain(type, field, DexClass::findInstanceTarget);
  }

  /**
   * Lookup static field starting in type and following the super chain.
   */
  public DexEncodedField lookupStaticTarget(DexType type, DexField field) {
    assert type.isClassType();
    DexEncodedField target = lookupTargetAlongSuperChain(type, field, DexClass::findStaticTarget);
    if (target == null) {
      target = lookupTargetAlongInterfaceChain(type, field, DexClass::findStaticTarget);
    }
    return target;
  }

  /**
   * Traverse along the super chain until lookup returns non-null value.
   */
  private <S extends DexItem, T extends Descriptor<S, T>> S lookupTargetAlongSuperChain(
      DexType type,
      T desc,
      BiFunction<DexClass, T, S> lookup) {
    assert type != null;
    DexClass holder = definitionFor(type);
    while (holder != null) {
      S result = lookup.apply(holder, desc);
      if (result != null) {
        return result;
      }
      if (holder.superType == null) {
        return null;
      }
      holder = definitionFor(holder.superType);
    }
    return null;
  }

  /**
   * Traverse along the super chain and the interface chains until lookup returns non-null value.
   */
  private <S extends DexItem, T extends Descriptor<S, T>> S lookupTargetAlongSuperAndInterfaceChain(
      DexType type,
      T desc,
      BiFunction<DexClass, T, S> lookup) {
    DexClass holder = definitionFor(type);
    if (holder == null) {
      return null;
    }
    S result = lookup.apply(holder, desc);
    if (result != null) {
      return result;
    }
    if (holder.superType != null) {
      result = lookupTargetAlongSuperAndInterfaceChain(holder.superType, desc, lookup);
      if (result != null) {
        return result;
      }
    }
    for (DexType iface : holder.interfaces.values) {
      result = lookupTargetAlongSuperAndInterfaceChain(iface, desc, lookup);
      if (result != null) {
        return result;
      }
    }
    return null;
  }

  private boolean isDefaultMethod(DexItem dexItem) {
    return dexItem != null && dexItem instanceof DexEncodedMethod &&
        !((DexEncodedMethod) dexItem).accessFlags.isStatic() &&
        ((DexEncodedMethod) dexItem).getCode() != null;
  }

  /** Returns if <code>interface1</code> is a super interface of <code>interface2</code> */
  private boolean isSuperInterfaceOf(DexType interface1, DexType interface2) {
    assert definitionFor(interface1).isInterface();
    DexClass holder = definitionFor(interface2);
    assert holder.isInterface();
    for (DexType iface : holder.interfaces.values) {
      if (iface == interface1 || isSuperInterfaceOf(interface1, iface)) {
        return true;
      }
    }
    return false;
  }

  private <S extends DexItem> S resolveAmbiguousResult(S previousResult, S newResult) {
    // For default methods return the item found lowest in the interface hierarchy. Different
    // implementations can come from different paths in a diamond.
    // See §9.4.1 in The Java® Language Specification, Java SE 8 Edition.
    if (previousResult != null
        && previousResult != newResult
        && isDefaultMethod(previousResult)
        && isDefaultMethod(newResult)) {
      DexEncodedMethod previousMethod = (DexEncodedMethod) previousResult;
      DexEncodedMethod newMethod = (DexEncodedMethod) newResult;
      if (isSuperInterfaceOf(previousMethod.method.getHolder(), newMethod.method.getHolder())) {
        return newResult;
      }
      if (isSuperInterfaceOf(newMethod.method.getHolder(), previousMethod.method.getHolder())) {
        return previousResult;
      }
      throw new CompilationError("Duplicate default methods named "
          + previousResult.toSourceString()
          + " are inherited from the types "
          + previousMethod.method.holder.getName()
          + " and "
          + newMethod.method.holder.getName());
    } else {
      // Return the first item found for everything except default methods.
      return previousResult != null ? previousResult : newResult;
    }
  }

  /**
   * Traverse along the interface chains until lookup returns non-null value.
   */
  private <S extends DexItem, T extends Descriptor<S, T>> S lookupTargetAlongInterfaceChain(
      DexType type,
      T desc,
      BiFunction<DexClass, T, S> lookup) {
    DexClass holder = definitionFor(type);
    if (holder == null) {
      // TODO(herhut): The subtype hierarchy is broken. Handle this case.
      return null;
    }
    S result = null;
    for (DexType iface : holder.interfaces.values) {
      S localResult = lookupTargetAlongSuperAndInterfaceChain(iface, desc, lookup);
      if (localResult != null) {
        result = resolveAmbiguousResult(result, localResult);
      }
    }
    if (holder.superType != null) {
      S localResult = lookupTargetAlongInterfaceChain(holder.superType, desc, lookup);
      if (localResult != null) {
        result = resolveAmbiguousResult(result, localResult);
      }
    }
    return result;
  }

  public DexEncodedMethod lookup(Type type, DexMethod target) {
    DexEncodedMethod definition;
    DexType holder = target.getHolder();
    if (!holder.isClassType()) {
      return null;
    }
    if (type == Type.VIRTUAL || type == Type.INTERFACE) {
      definition = lookupVirtualDefinition(holder, target);
    } else if (type == Type.DIRECT) {
      definition = lookupDirectTarget(target);
    } else if (type == Type.STATIC) {
      definition = lookupStaticTarget(target);
    } else if (type == Type.SUPER) {
      definition = lookupVirtualTarget(holder, target);
    } else {
      return null;
    }
    return definition;
  }

  public boolean hasSubtyping() {
    return false;
  }

  public AppInfoWithSubtyping withSubtyping() {
    return null;
  }

  public boolean hasLiveness() {
    return false;
  }

  public AppInfoWithLiveness withLiveness() {
    return null;
  }

  public void registerNewType(DexType newType, DexType superType) {
    // We do not track subtyping relationships in the basic AppInfo. So do nothing.
  }

  public boolean isInMainDexList(DexType type) {
    return app.mainDexList.contains(type);
  }

  public List<DexClass> getSuperTypeClasses(DexType type) {
    List<DexClass> result = new ArrayList<>();
    do {
      DexClass clazz = definitionFor(type);
      if (clazz == null) {
        break;
      }
      result.add(clazz);
      type = clazz.superType;
    } while (type != null);
    return result;
  }
}
