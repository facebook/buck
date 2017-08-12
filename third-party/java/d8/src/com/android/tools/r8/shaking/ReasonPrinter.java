// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItem;
import com.android.tools.r8.graph.DexType;
import com.google.common.collect.Sets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.Set;

public class ReasonPrinter {

  private final Set<DexItem> itemsQueried;
  private ReasonFormatter formatter;

  private final Map<DexEncodedField, KeepReason> liveFields;
  private final Map<DexEncodedMethod, KeepReason> liveMethods;
  private final Map<DexItem, KeepReason> reachablityReasons;
  private final Map<DexType, KeepReason> instantiatedTypes;

  ReasonPrinter(Set<DexItem> itemsQueried, Map<DexEncodedField, KeepReason> liveFields,
      Map<DexEncodedMethod, KeepReason> liveMethods, Map<DexItem, KeepReason> reachablityReasons,
      Map<DexType, KeepReason> instantiatedTypes) {
    this.itemsQueried = itemsQueried;
    this.liveFields = liveFields;
    this.liveMethods = liveMethods;
    this.reachablityReasons = reachablityReasons;
    this.instantiatedTypes = instantiatedTypes;
  }

  public void run(DexApplication application) {
    // TODO(herhut): Instead of traversing the app, sort the queried items.
    formatter = new ReasonFormatter();
    for (DexClass clazz : application.classes()) {
      if (itemsQueried.contains(clazz)) {
        printReasonFor(clazz);
      }
      Arrays.stream(clazz.staticFields()).filter(itemsQueried::contains)
          .forEach(this::printReasonFor);
      Arrays.stream(clazz.instanceFields()).filter(itemsQueried::contains)
          .forEach(this::printReasonFor);
      Arrays.stream(clazz.directMethods()).filter(itemsQueried::contains)
          .forEach(this::printReasonFor);
      Arrays.stream(clazz.virtualMethods()).filter(itemsQueried::contains)
          .forEach(this::printReasonFor);
    }
  }

  private void printNoIdeaWhy(DexItem item, ReasonFormatter formatter) {
    formatter.startItem(item);
    formatter.pushEmptyPrefix();
    formatter.addReason("is kept for unknown reason.");
    formatter.popPrefix();
    formatter.endItem();
  }

  private void printOnlyAbstractShell(DexItem item, ReasonFormatter formatter) {
    formatter.startItem(item);
    KeepReason reachableReason = reachablityReasons.get(item);
    if (reachableReason != null) {
      formatter.pushPrefix(
          "is not kept, only its abstract declaration is needed because it ");
      reachableReason.print(formatter);
      formatter.popPrefix();
    } else {
      formatter.pushEmptyPrefix();
      formatter.addReason("is not kept, only its abstract declaration is.");
      formatter.popPrefix();
    }
    formatter.endItem();
  }

  private void printReasonFor(DexClass item) {
    KeepReason reason = instantiatedTypes.get(item.type);
    if (reason == null) {
      if (item.accessFlags.isAbstract()) {
        printOnlyAbstractShell(item, formatter);
      } else {
        printNoIdeaWhy(item, formatter);
      }
    } else {
      formatter.startItem(item);
      formatter.pushIsLivePrefix();
      reason.print(formatter);
      formatter.popPrefix();
      formatter.endItem();
    }
  }

  private void printReasonFor(DexEncodedMethod item) {
    KeepReason reasonLive = liveMethods.get(item);
    if (reasonLive == null) {
      if (item.accessFlags.isAbstract()) {
        printOnlyAbstractShell(item, formatter);
      } else {
        printNoIdeaWhy(item.method, formatter);
      }
    } else {
      formatter.addMethodReferenceReason(item);
    }
  }

  private void printReasonFor(DexEncodedField item) {
    KeepReason reason = liveFields.get(item);
    if (reason == null) {
      printNoIdeaWhy(item.field, formatter);
    } else {
      formatter.startItem(item.field);
      formatter.pushIsLivePrefix();
      reason.print(formatter);
      formatter.popPrefix();
      reason = reachablityReasons.get(item);
      if (reason != null) {
        formatter.pushIsReachablePrefix();
        reason.print(formatter);
        formatter.popPrefix();
      }
      formatter.endItem();
    }
  }

  class ReasonFormatter {

    private Set<DexItem> seen = Sets.newIdentityHashSet();
    private Deque<String> prefixes = new ArrayDeque<>();

    private int indentation = -1;

    void pushIsLivePrefix() {
      prefixes.push("is live because ");
    }

    void pushIsReachablePrefix() {
      prefixes.push("is reachable because ");
    }

    void pushPrefix(String prefix) {
      prefixes.push(prefix);
    }

    void pushEmptyPrefix() {
      prefixes.push("");
    }

    void popPrefix() {
      prefixes.pop();
    }

    void startItem(DexItem item) {
      indentation++;
      indent();
      System.out.println(item.toSourceString());
    }

    private void indent() {
      for (int i = 0; i < indentation; i++) {
        System.out.print("  ");
      }
    }

    void addReason(String thing) {
      indent();
      System.out.print("|- ");
      String prefix = prefixes.peek();
      System.out.print(prefix);
      System.out.println(thing);
    }

    void addMessage(String thing) {
      indent();
      System.out.print("|  ");
      System.out.println(thing);
    }

    void endItem() {
      indentation--;
    }

    void addMethodReferenceReason(DexEncodedMethod method) {
      if (!seen.add(method.method)) {
        return;
      }
      startItem(method);
      KeepReason reason = reachablityReasons.get(method);
      if (reason != null) {
        pushIsReachablePrefix();
        reason.print(this);
        popPrefix();
      }
      reason = liveMethods.get(method);
      if (reason != null) {
        pushIsLivePrefix();
        reason.print(this);
        popPrefix();
      }
      endItem();
    }

    void addTypeLivenessReason(DexType type) {
      if (!seen.add(type)) {
        return;
      }
      startItem(type);
      pushIsLivePrefix();
      KeepReason reason = instantiatedTypes.get(type);
      if (reason != null) {
        reason.print(this);
      }
      popPrefix();
      endItem();
    }
  }

  public static ReasonPrinter getNoOpPrinter() {
    return new NoOpReasonPrinter();
  }

  private static class NoOpReasonPrinter extends ReasonPrinter {

    NoOpReasonPrinter() {
      super(Collections.emptySet(), Collections.emptyMap(), Collections.emptyMap(),
          Collections.emptyMap(), Collections.emptyMap());
    }

    @Override
    public void run(DexApplication application) {
      // Intentionally left empty.
    }
  }
}
