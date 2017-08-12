// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.KeyedDexItem;
import com.android.tools.r8.graph.PresortedComparable;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.StringDiagnostic;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class TreePruner {

  private DexApplication application;
  private final AppInfoWithLiveness appInfo;
  private final InternalOptions options;
  private final UsagePrinter usagePrinter;
  private final Set<DexType> prunedTypes = Sets.newIdentityHashSet();

  public TreePruner(
      DexApplication application, AppInfoWithLiveness appInfo, InternalOptions options) {
    this.application = application;
    this.appInfo = appInfo;
    this.options = options;
    this.usagePrinter = options.proguardConfiguration.isPrintUsage()
        ? new UsagePrinter() : UsagePrinter.DONT_PRINT;
  }

  public DexApplication run() {
    application.timing.begin("Pruning application...");
    if (options.debugKeepRules && !options.skipMinification) {

      options.diagnosticsHandler.info(
          new StringDiagnostic(
              "Debugging keep rules on a minified build might yield broken builds, as "
                  + "minification also depends on the used keep rules. We recommend using "
                  + "--skip-minification."));
    }
    DexApplication result;
    try {
      result = removeUnused(application).appendDeadCode(usagePrinter.toByteArray()).build();
    } finally {
      application.timing.end();
    }
    return result;
  }

  private DexApplication.Builder<?> removeUnused(DexApplication application) {
    return application.builder()
        .replaceProgramClasses(getNewProgramClasses(application.classes()));
  }

  private List<DexProgramClass> getNewProgramClasses(List<DexProgramClass> classes) {
    List<DexProgramClass> newClasses = new ArrayList<>();
    for (DexProgramClass clazz : classes) {
      if (!appInfo.liveTypes.contains(clazz.type)) {
        // The class is completely unused and we can remove it.
        if (Log.ENABLED) {
          Log.debug(getClass(), "Removing class: " + clazz);
        }
        prunedTypes.add(clazz.type);
        usagePrinter.printUnusedClass(clazz);
      } else {
        newClasses.add(clazz);
        if (!appInfo.instantiatedTypes.contains(clazz.type) &&
            (!options.debugKeepRules || !clazz.hasDefaultInitializer())) {
          // The class is only needed as a type but never instantiated. Make it abstract to reflect
          // this.
          if (clazz.accessFlags.isFinal()) {
            // We cannot mark this class abstract, as it is final (not supported on Android).
            // However, this might extend an abstract class and we might have removed the
            // corresponding methods in this class. This might happen if we only keep this
            // class around for its constants.
            // For now, we remove the final flag to still be able to mark it abstract.
            clazz.accessFlags.unsetFinal();
          }
          clazz.accessFlags.setAbstract();
        }
        // The class is used and must be kept. Remove the unused fields and methods from
        // the class.
        usagePrinter.visiting(clazz);
        clazz.setDirectMethods(reachableMethods(clazz.directMethods(), clazz));
        clazz.setVirtualMethods(reachableMethods(clazz.virtualMethods(), clazz));
        clazz.setInstanceFields(reachableFields(clazz.instanceFields()));
        clazz.setStaticFields(reachableFields(clazz.staticFields()));
        usagePrinter.visited();
      }
    }
    return newClasses;
  }

  private <S extends PresortedComparable<S>, T extends KeyedDexItem<S>> int firstUnreachableIndex(
      T[] items, Set<S> live) {
    for (int i = 0; i < items.length; i++) {
      if (!live.contains(items[i].getKey())) {
        return i;
      }
    }
    return -1;
  }

  private boolean isDefaultConstructor(DexEncodedMethod method) {
    return method.isInstanceInitializer()
        && method.method.proto.parameters.isEmpty();
  }

  private DexEncodedMethod[] reachableMethods(DexEncodedMethod[] methods, DexClass clazz) {
    int firstUnreachable = firstUnreachableIndex(methods, appInfo.liveMethods);
    // Return the original array if all methods are used.
    if (firstUnreachable == -1) {
      return methods;
    }
    ArrayList<DexEncodedMethod> reachableMethods = new ArrayList<>(methods.length);
    for (int i = 0; i < firstUnreachable; i++) {
      reachableMethods.add(methods[i]);
    }
    for (int i = firstUnreachable; i < methods.length; i++) {
      DexEncodedMethod method = methods[i];
      if (appInfo.liveMethods.contains(methods[i].getKey())) {
        reachableMethods.add(method);
      } else if (options.debugKeepRules && isDefaultConstructor(method)) {
        // Keep the method but rewrite its body, if it has one.
        reachableMethods.add(methods[i].accessFlags.isAbstract()
            ? method
            : method.toMethodThatLogsError(application.dexItemFactory));
      } else if (appInfo.targetedMethods.contains(method.getKey())) {
        if (Log.ENABLED) {
          Log.debug(getClass(), "Making method %s abstract.", method.method);
        }
        // Final classes cannot be abstract, so we have to keep the method in that case.
        // Also some other kinds of methods cannot be abstract, so keep them around.
        boolean allowAbstract = clazz.accessFlags.isAbstract()
            && !method.accessFlags.isFinal()
            && !method.accessFlags.isNative()
            && !method.accessFlags.isStrict()
            && !method.accessFlags.isSynchronized();
        // By construction, private and static methods cannot be reachable but non-live.
        assert !method.accessFlags.isPrivate() && !method.accessFlags.isStatic();
        reachableMethods.add(allowAbstract
            ? method.toAbstractMethod()
            : method.toEmptyThrowingMethod());
      } else {
        if (Log.ENABLED) {
          Log.debug(getClass(), "Removing method %s.", method.method);
        }
        usagePrinter.printUnusedMethod(method);
      }
    }
    return reachableMethods.toArray(new DexEncodedMethod[reachableMethods.size()]);
  }

  private DexEncodedField[] reachableFields(DexEncodedField[] fields) {
    int firstUnreachable = firstUnreachableIndex(fields, appInfo.liveFields);
    // Return the original array if all fields are used.
    if (firstUnreachable == -1) {
      return fields;
    }
    if (Log.ENABLED) {
      Log.debug(getClass(), "Removing field: " + fields[firstUnreachable]);
    }
    usagePrinter.printUnusedField(fields[firstUnreachable]);
    ArrayList<DexEncodedField> reachableFields = new ArrayList<>(fields.length);
    for (int i = 0; i < firstUnreachable; i++) {
      reachableFields.add(fields[i]);
    }
    for (int i = firstUnreachable + 1; i < fields.length; i++) {
      DexEncodedField field = fields[i];
      if (appInfo.liveFields.contains(field.getKey())) {
        reachableFields.add(field);
      } else {
        if (Log.ENABLED) {
          Log.debug(getClass(), "Removing field: " + field);
        }
        usagePrinter.printUnusedField(field);
      }
    }
    return reachableFields.toArray(new DexEncodedField[reachableFields.size()]);
  }

  public Collection<DexType> getRemovedClasses() {
    return Collections.unmodifiableCollection(prunedTypes);
  }
}
