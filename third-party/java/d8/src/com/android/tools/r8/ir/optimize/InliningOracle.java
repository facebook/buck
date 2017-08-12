// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.optimize;

import com.android.tools.r8.graph.Code;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.android.tools.r8.ir.code.InvokePolymorphic;
import com.android.tools.r8.ir.code.InvokeStatic;
import com.android.tools.r8.ir.conversion.CallSiteInformation;
import com.android.tools.r8.ir.optimize.Inliner.InlineAction;
import com.android.tools.r8.ir.optimize.Inliner.Reason;
import com.android.tools.r8.logging.Log;
import java.util.function.Predicate;

/**
 * The InliningOracle contains information needed for when inlining
 * other methods into @method.
 */
public class InliningOracle {

  private static final int INLINING_INSTRUCTION_LIMIT = 5;

  private final Inliner inliner;
  private final DexEncodedMethod method;
  private final CallSiteInformation callSiteInformation;
  private final Predicate<DexEncodedMethod> isProcessedConcurrently;
  private final InliningInfo info;

  InliningOracle(
      Inliner inliner,
      DexEncodedMethod method,
      CallSiteInformation callSiteInformation,
      Predicate<DexEncodedMethod> isProcessedConcurrently) {
    this.inliner = inliner;
    this.method = method;
    this.callSiteInformation = callSiteInformation;
    this.isProcessedConcurrently = isProcessedConcurrently;
    info = Log.ENABLED ? new InliningInfo(method) : null;
  }

  void finish() {
    if (Log.ENABLED) {
      Log.debug(getClass(), info.toString());
    }
  }

  private DexEncodedMethod validateCandidate(InvokeMethod invoke) {
    DexEncodedMethod candidate = invoke.computeSingleTarget(inliner.appInfo);
    if ((candidate == null)
        || (candidate.getCode() == null)
        || inliner.appInfo.definitionFor(candidate.method.getHolder()).isLibraryClass()) {
      if (info != null) {
        info.exclude(invoke, "No inlinee");
      }
      return null;
    }
    return candidate;
  }

  private Reason computeInliningReason(DexEncodedMethod target) {
    if (target.getOptimizationInfo().forceInline()) {
      return Reason.FORCE;
    }
    if (inliner.appInfo.hasLiveness()
        && inliner.appInfo.withLiveness().alwaysInline.contains(target)) {
      return Reason.ALWAYS;
    }
    if (callSiteInformation.hasSingleCallSite(target)) {
      return Reason.SINGLE_CALLER;
    }
    if (isDoubleInliningTarget(target)) {
      return Reason.DUAL_CALLER;
    }
    return Reason.SIMPLE;
  }

  private boolean canInlineStaticInvoke(DexEncodedMethod method, DexEncodedMethod target) {
    // Only proceed with inlining a static invoke if:
    // - the holder for the target equals the holder for the method, or
    // - there is no class initializer.
    DexType targetHolder = target.method.getHolder();
    if (method.method.getHolder() == targetHolder) {
      return true;
    }
    DexClass clazz = inliner.appInfo.definitionFor(targetHolder);
    return (clazz != null)
        && (!clazz.hasNonTrivialClassInitializer())
        && (!clazz.defaultValuesForStaticFieldsMayTriggerAllocation());
  }

  private synchronized boolean isDoubleInliningTarget(DexEncodedMethod candidate) {
    // 10 is found from measuring.
    return callSiteInformation.hasDoubleCallSite(candidate)
        && candidate.getCode().isDexCode()
        && (candidate.getCode().asDexCode().instructions.length <= 10);
  }

  private boolean passesInliningConstraints(InvokeMethod invoke, DexEncodedMethod candidate,
      Reason reason) {
    if (method == candidate) {
      // Cannot handle recursive inlining at this point.
      // Force inlined method should never be recursive.
      assert !candidate.getOptimizationInfo().forceInline();
      if (info != null) {
        info.exclude(invoke, "direct recursion");
      }
      return false;
    }

    if (reason != Reason.FORCE && isProcessedConcurrently.test(candidate)) {
      if (info != null) {
        info.exclude(invoke, "is processed in parallel");
      }
      return false;
    }

    // Abort inlining attempt if method -> target access is not right.
    if (!inliner.hasInliningAccess(method, candidate)) {
      if (info != null) {
        info.exclude(invoke, "target does not have right access");
      }
      return false;
    }

    DexClass holder = inliner.appInfo.definitionFor(candidate.method.getHolder());
    if (holder.isInterface()) {
      // Art978_virtual_interfaceTest correctly expects an IncompatibleClassChangeError exception at
      // runtime.
      if (info != null) {
        info.exclude(invoke, "Do not inline target if method holder is an interface class");
      }
      return false;
    }

    if (holder.isLibraryClass()) {
      // Library functions should not be inlined.
      return false;
    }

    // Don't inline if target is synchronized.
    if (candidate.accessFlags.isSynchronized()) {
      if (info != null) {
        info.exclude(invoke, "target is synchronized");
      }
      return false;
    }

    // Attempt to inline a candidate that is only called twice.
    if ((reason == Reason.DUAL_CALLER) && (inliner.doubleInlining(method, candidate) == null)) {
      if (info != null) {
        info.exclude(invoke, "target is not ready for double inlining");
      }
      return false;
    }

    if (reason == Reason.SIMPLE) {
      // If we are looking for a simple method, only inline if actually simple.
      Code code = candidate.getCode();
      if (code.estimatedSizeForInlining() > INLINING_INSTRUCTION_LIMIT) {
        return false;
      }
    }
    return true;
  }

  public InlineAction computeForInvokeWithReceiver(InvokeMethodWithReceiver invoke) {
    boolean receiverIsNeverNull = invoke.receiverIsNeverNull();
    if (!receiverIsNeverNull) {
      if (info != null) {
        info.exclude(invoke, "receiver for candidate can be null");
      }
      return null;
    }
    DexEncodedMethod candidate = validateCandidate(invoke);
    if (candidate == null) {
      return null;
    }

    Reason reason = computeInliningReason(candidate);
    if (!candidate.isInliningCandidate(method, reason, inliner.appInfo)) {
      // Abort inlining attempt if the single target is not an inlining candidate.
      if (info != null) {
        info.exclude(invoke, "target is not identified for inlining");
      }
      return null;
    }

    if (!passesInliningConstraints(invoke, candidate, reason)) {
      return null;
    }

    if (info != null) {
      info.include(invoke.getType(), candidate);
    }
    return new InlineAction(candidate, invoke, reason);
  }

  public InlineAction computeForInvokeStatic(InvokeStatic invoke) {
    DexEncodedMethod candidate = validateCandidate(invoke);
    if (candidate == null) {
      return null;
    }

    Reason reason = computeInliningReason(candidate);
    // Determine if this should be inlined no matter how big it is.
    if (!candidate.isInliningCandidate(method, reason, inliner.appInfo)) {
      // Abort inlining attempt if the single target is not an inlining candidate.
      if (info != null) {
        info.exclude(invoke, "target is not identified for inlining");
      }
      return null;
    }

    // Abort inlining attempt if we can not guarantee class for static target has been initialized.
    if (!canInlineStaticInvoke(method, candidate)) {
      if (info != null) {
        info.exclude(invoke, "target is static but we cannot guarantee class has been initialized");
      }
      return null;
    }

    if (!passesInliningConstraints(invoke, candidate, reason)) {
      return null;
    }

    if (info != null) {
      info.include(invoke.getType(), candidate);
    }
    return new InlineAction(candidate, invoke, reason);
  }

  public InlineAction computeForInvokePolymorpic(InvokePolymorphic invoke) {
    // TODO: No inlining of invoke polymorphic for now.
    if (info != null) {
      info.exclude(invoke, "inlining through invoke signature polymorpic is not supported");
    }
    return null;
  }
}
