// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItem;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.shaking.ReasonPrinter.ReasonFormatter;

// TODO(herhut): Canonicalize reason objects.
abstract class KeepReason {

  static KeepReason dueToKeepRule(ProguardKeepRule rule) {
    return new DueToKeepRule(rule);
  }

  static KeepReason instantiatedIn(DexEncodedMethod method) {
    return new InstatiatedIn(method);
  }

  public static KeepReason invokedViaSuperFrom(DexEncodedMethod from) {
    return new InvokedViaSuper(from);
  }

  public static KeepReason reachableFromLiveType(DexType type) {
    return new ReachableFromLiveType(type);
  }

  public static KeepReason invokedFrom(DexEncodedMethod method) {
    return new InvokedFrom(method);
  }

  public static KeepReason isLibraryMethod() {
    return new IsLibraryMethod();
  }

  public static KeepReason fieldReferencedIn(DexEncodedMethod method) {
    return new ReferenedFrom(method);
  }

  public static KeepReason referencedInAnnotation(DexItem holder) {
    return new ReferencedInAnnotation(holder);
  }

  public abstract void print(ReasonFormatter formatter);

  private static class DueToKeepRule extends KeepReason {

    private final ProguardKeepRule keepRule;

    private DueToKeepRule(ProguardKeepRule keepRule) {
      this.keepRule = keepRule;
    }

    @Override
    public void print(ReasonFormatter formatter) {
      formatter.addReason("referenced in keep rule:");
      formatter.addMessage("  " + keepRule + " {");
      int ruleCount = 0;
      for (ProguardMemberRule memberRule : keepRule.getMemberRules()) {
        formatter.addMessage("    " + memberRule);
        if (++ruleCount > 10) {
          formatter.addMessage("      <...>");
          break;
        }
      }
      formatter.addMessage("  };");
    }
  }

  private abstract static class BasedOnOtherMethod extends KeepReason {

    private final DexEncodedMethod method;

    private BasedOnOtherMethod(DexEncodedMethod method) {
      this.method = method;
    }

    abstract String getKind();

    @Override
    public void print(ReasonFormatter formatter) {
      formatter.addReason("is " + getKind() + " " + method.toSourceString());
      formatter.addMethodReferenceReason(method);
    }

  }

  private static class InstatiatedIn extends BasedOnOtherMethod {

    private InstatiatedIn(DexEncodedMethod method) {
      super(method);
    }

    @Override
    String getKind() {
      return "instantiated in";
    }
  }

  private static class InvokedViaSuper extends BasedOnOtherMethod {

    private InvokedViaSuper(DexEncodedMethod method) {
      super(method);
    }

    @Override
    String getKind() {
      return "invoked via super from";
    }
  }

  private static class InvokedFrom extends BasedOnOtherMethod {

    private InvokedFrom(DexEncodedMethod method) {
      super(method);
    }

    @Override
    String getKind() {
      return "invoked from";
    }
  }

  private static class ReferenedFrom extends BasedOnOtherMethod {

    private ReferenedFrom(DexEncodedMethod method) {
      super(method);
    }

    @Override
    String getKind() {
      return "referenced from";
    }
  }

  private static class ReachableFromLiveType extends KeepReason {

    private final DexType type;

    private ReachableFromLiveType(DexType type) {
      this.type = type;
    }

    @Override
    public void print(ReasonFormatter formatter) {
      formatter.addReason("is reachable from type " + type.toSourceString());
      formatter.addTypeLivenessReason(type);
    }
  }

  private static class IsLibraryMethod extends KeepReason {

    private IsLibraryMethod() {
    }

    @Override
    public void print(ReasonFormatter formatter) {
      formatter.addReason("is defined in a library.");
    }
  }

  private static class ReferencedInAnnotation extends KeepReason {

    private final DexItem holder;

    private ReferencedInAnnotation(DexItem holder) {
      this.holder = holder;
    }

    @Override
    public void print(ReasonFormatter formatter) {
      formatter.addReason("is referenced in annotation on " + holder.toSourceString());
    }
  }
}
