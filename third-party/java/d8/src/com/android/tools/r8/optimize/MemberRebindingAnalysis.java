// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.optimize;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.GraphLense;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.google.common.collect.Sets;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

public class MemberRebindingAnalysis {

  private final AppInfoWithLiveness appInfo;
  private final GraphLense lense;
  private final GraphLense.Builder builder = GraphLense.builder();

  public MemberRebindingAnalysis(AppInfoWithLiveness appInfo, GraphLense lense) {
    assert lense.isContextFree();
    this.appInfo = appInfo;
    this.lense = lense;
  }

  private DexMethod validTargetFor(DexMethod target, DexMethod original,
      BiFunction<DexClass, DexMethod, DexEncodedMethod> lookup) {
    DexClass clazz = appInfo.definitionFor(target.getHolder());
    assert clazz != null;
    if (!clazz.isLibraryClass()) {
      return target;
    }
    DexType newHolder;
    if (clazz.isInterface()) {
      newHolder = firstLibraryClassForInterfaceTarget(target, original.getHolder(), lookup);
    } else {
      newHolder = firstLibraryClass(target.getHolder(), original.getHolder());
    }
    return appInfo.dexItemFactory.createMethod(newHolder, original.proto, original.name);
  }

  private DexField validTargetFor(DexField target, DexField original,
      BiFunction<DexClass, DexField, DexEncodedField> lookup) {
    DexClass clazz = appInfo.definitionFor(target.getHolder());
    assert clazz != null;
    if (!clazz.isLibraryClass()) {
      return target;
    }
    DexType newHolder;
    if (clazz.isInterface()) {
      newHolder = firstLibraryClassForInterfaceTarget(target, original.getHolder(), lookup);
    } else {
      newHolder = firstLibraryClass(target.getHolder(), original.getHolder());
    }
    return appInfo.dexItemFactory.createField(newHolder, original.type, original.name);
  }

  private <T> DexType firstLibraryClassForInterfaceTarget(T target, DexType current,
      BiFunction<DexClass, T, ?> lookup) {
    DexClass clazz = appInfo.definitionFor(current);
    Object potential = lookup.apply(clazz, target);
    if (potential != null) {
      // Found, return type.
      return current;
    }
    if (clazz.superType != null) {
      DexType matchingSuper = firstLibraryClassForInterfaceTarget(target, clazz.superType, lookup);
      if (matchingSuper != null) {
        // Found in supertype, return first libray class.
        return clazz.isLibraryClass() ? current : matchingSuper;
      }
    }
    for (DexType iface : clazz.interfaces.values) {
      DexType matchingIface = firstLibraryClassForInterfaceTarget(target, iface, lookup);
      if (matchingIface != null) {
        // Found in interface, return first library class.
        return clazz.isLibraryClass() ? current : matchingIface;
      }
    }
    return null;
  }

  private DexType firstLibraryClass(DexType top, DexType bottom) {
    assert appInfo.definitionFor(top).isLibraryClass();
    DexClass searchClass = appInfo.definitionFor(bottom);
    while (!searchClass.isLibraryClass()) {
      searchClass = appInfo.definitionFor(searchClass.superType);
    }
    return searchClass.type;
  }

  private DexEncodedMethod virtualLookup(DexMethod method) {
    return appInfo.lookupVirtualDefinition(method.getHolder(), method);
  }

  private DexEncodedMethod superLookup(DexMethod method) {
    return appInfo.lookupVirtualTarget(method.getHolder(), method);
  }

  private void computeMethodRebinding(Set<DexMethod> methods,
      Function<DexMethod, DexEncodedMethod> lookupTarget,
      BiFunction<DexClass, DexMethod, DexEncodedMethod> lookupTargetOnClass,
      BiConsumer<DexProgramClass, DexEncodedMethod> addMethod) {
    for (DexMethod method : methods) {
      method = lense.lookupMethod(method, null);
      // We can safely ignore array types, as the corresponding methods are defined in a library.
      if (!method.getHolder().isClassType()) {
        continue;
      }
      DexClass originalClass = appInfo.definitionFor(method.holder);
      // We can safely ignore calls to library classes, as those cannot be rebound.
      if (originalClass == null || originalClass.isLibraryClass()) {
        continue;
      }
      DexEncodedMethod target = lookupTarget.apply(method);
      // Rebind to the lowest library class or program class.
      if (target != null && target.method != method) {
        DexClass targetClass = appInfo.definitionFor(target.method.holder);
        // If the targetclass is not public but the targeted method is, we might run into
        // visibility problems when rebinding.
        if (!targetClass.accessFlags.isPublic() && target.accessFlags.isPublic()) {
          // If the original class is public and this method is public, it might have been called
          // from anywhere, so we need a bridge. Likewise, if the original is in a different
          // package, we might need a bridge, too.
          String packageDescriptor =
              originalClass.accessFlags.isPublic() ? null : method.holder.getPackageDescriptor();
          if (packageDescriptor == null
              || !packageDescriptor.equals(targetClass.type.getPackageDescriptor())) {
            DexProgramClass bridgeHolder = findBridgeMethodHolder(originalClass, targetClass,
                packageDescriptor);
            assert bridgeHolder != null;
            DexEncodedMethod bridgeMethod = target
                .toForwardingMethod(bridgeHolder, appInfo.dexItemFactory);
            addMethod.accept(bridgeHolder, bridgeMethod);
            assert lookupTarget.apply(method) == bridgeMethod;
            target = bridgeMethod;
          }
        }
        builder.map(method, validTargetFor(target.method, method, lookupTargetOnClass));
      }
    }
  }

  private DexProgramClass findBridgeMethodHolder(DexClass originalClass, DexClass targetClass,
      String packageDescriptor) {
    if (originalClass == targetClass || originalClass.isLibraryClass()) {
      return null;
    }
    DexProgramClass newHolder = null;
    // Recurse through supertype chain.
    if (originalClass.superType.isSubtypeOf(targetClass.type, appInfo)) {
      DexClass superClass = appInfo.definitionFor(originalClass.superType);
      newHolder = findBridgeMethodHolder(superClass, targetClass, packageDescriptor);
    } else {
      for (DexType iface : originalClass.interfaces.values) {
        if (iface.isSubtypeOf(targetClass.type, appInfo)) {
          DexClass interfaceClass = appInfo.definitionFor(iface);
          newHolder = findBridgeMethodHolder(interfaceClass, targetClass, packageDescriptor);
        }
      }
    }
    if (newHolder != null) {
      // A supertype fulfills the visibility requirements.
      return newHolder;
    } else if (originalClass.accessFlags.isPublic()
        || originalClass.type.getPackageDescriptor().equals(packageDescriptor)) {
      // This class is visible. Return it if it is a program class, otherwise null.
      return originalClass.asProgramClass();
    }
    return null;
  }

  private void computeFieldRebinding(Set<DexField> fields,
      BiFunction<DexType, DexField, DexEncodedField> lookup,
      BiFunction<DexClass, DexField, DexEncodedField> lookupTargetOnClass) {
    for (DexField field : fields) {
      field = lense.lookupField(field, null);
      DexEncodedField target = lookup.apply(field.getHolder(), field);
      // Rebind to the lowest library class or program class. Do not rebind accesses to fields that
      // are not public, as this might lead to access violation errors.
      if (target != null && target.field != field && isVisibleFromOtherClasses(target)) {
        builder.map(field, validTargetFor(target.field, field, lookupTargetOnClass));
      }
    }
  }

  private boolean isVisibleFromOtherClasses(DexEncodedField field) {
    // If the field is not public, the visibility on the class can not be a further constraint.
    if (!field.accessFlags.isPublic()) {
      return true;
    }
    // If the field is public, then a non-public holder class will further constrain visibility.
    return appInfo.definitionFor(field.field.getHolder()).accessFlags.isPublic();
  }

  private static void privateMethodsCheck(DexProgramClass clazz, DexEncodedMethod method) {
    throw new Unreachable("Direct invokes should not require forwarding.");
  }

  public GraphLense run() {
    computeMethodRebinding(appInfo.virtualInvokes, this::virtualLookup,
        DexClass::findVirtualTarget, DexProgramClass::addVirtualMethod);
    computeMethodRebinding(appInfo.superInvokes, this::superLookup, DexClass::findVirtualTarget,
        DexProgramClass::addVirtualMethod);
    computeMethodRebinding(appInfo.directInvokes, appInfo::lookupDirectTarget,
        DexClass::findDirectTarget, MemberRebindingAnalysis::privateMethodsCheck);
    computeMethodRebinding(appInfo.staticInvokes, appInfo::lookupStaticTarget,
        DexClass::findDirectTarget, DexProgramClass::addStaticMethod);

    computeFieldRebinding(Sets.union(appInfo.staticFieldReads, appInfo.staticFieldWrites),
        appInfo::lookupStaticTarget, DexClass::findStaticTarget);
    computeFieldRebinding(Sets.union(appInfo.instanceFieldReads, appInfo.instanceFieldWrites),
        appInfo::lookupInstanceTarget, DexClass::findInstanceTarget);
    return builder.build(appInfo.dexItemFactory, lense);
  }
}
