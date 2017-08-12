// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.optimize;

import com.google.common.collect.Sets;

import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.optimize.InvokeSingleTargetExtractor.InvokeKind;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class VisibilityBridgeRemover {
  private final AppInfoWithSubtyping appInfo;
  private final DexApplication application;
  private final Set<DexEncodedMethod> unneededVisibilityBridges = Sets.newIdentityHashSet();

  public VisibilityBridgeRemover(AppInfoWithSubtyping appInfo, DexApplication application) {
    this.appInfo = appInfo;
    this.application = application;
  }

  private void identifyBridgeMethod(DexEncodedMethod method) {
    if (method.accessFlags.isBridge()) {
      InvokeSingleTargetExtractor targetExtractor = new InvokeSingleTargetExtractor();
      method.getCode().registerReachableDefinitions(targetExtractor);
      DexMethod target = targetExtractor.getTarget();
      InvokeKind kind = targetExtractor.getKind();
      if (target != null &&
          target.proto == method.method.proto) {
        assert !method.accessFlags.isPrivate() && !method.accessFlags.isConstructor();
        if (kind == InvokeKind.SUPER) {
          // This is a visibility forward, so check for the direct target.
          DexEncodedMethod targetMethod
              = appInfo.lookupVirtualDefinition(target.getHolder(), target);
          if (targetMethod != null && targetMethod.accessFlags.isPublic()) {
            if (Log.ENABLED) {
              Log.info(getClass(), "Removing visibility forwarding %s -> %s", method.method,
                  targetMethod.method);
            }
            unneededVisibilityBridges.add(method);
          }
        }
      }
    }
  }

  private void removeUnneededVisibilityBridges() {
    Set<DexType> classes = unneededVisibilityBridges.stream()
        .map(method -> method.method.getHolder())
        .collect(Collectors.toSet());
    for (DexType type : classes) {
      DexClass clazz = appInfo.definitionFor(type);
      clazz.setVirtualMethods(removeMethods(clazz.virtualMethods(), unneededVisibilityBridges));
    }
  }

  private DexEncodedMethod[] removeMethods(DexEncodedMethod[] methods,
      Set<DexEncodedMethod> removals) {
    assert methods != null;
    List<DexEncodedMethod> newMethods = Arrays.stream(methods)
        .filter(method -> !removals.contains(method))
        .collect(Collectors.toList());
    assert newMethods.size() < methods.length;
    return newMethods.toArray(new DexEncodedMethod[newMethods.size()]);
  }

  public DexApplication run() {
    for (DexClass clazz : appInfo.classes()) {
      clazz.forEachMethod(this::identifyBridgeMethod);
    }
    if (!unneededVisibilityBridges.isEmpty()) {
      removeUnneededVisibilityBridges();
    }
    return application;
  }

}
