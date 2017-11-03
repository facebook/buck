// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.optimize;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.graph.AccessFlags;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.GraphLense;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.DominatorTree;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionIterator;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.code.ValueNumberGenerator;
import com.android.tools.r8.ir.conversion.CallSiteInformation;
import com.android.tools.r8.ir.conversion.IRConverter;
import com.android.tools.r8.ir.conversion.LensCodeRewriter;
import com.android.tools.r8.ir.conversion.OptimizationFeedback;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.utils.InternalOptions;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class Inliner {

  protected final AppInfoWithSubtyping appInfo;
  private final GraphLense graphLense;
  private final InternalOptions options;

  // State for inlining methods which are known to be called twice.
  private boolean applyDoubleInlining = false;
  private final Set<DexEncodedMethod> doubleInlineCallers = Sets.newIdentityHashSet();
  private final Set<DexEncodedMethod> doubleInlineSelectedTargets = Sets.newIdentityHashSet();
  private final Map<DexEncodedMethod, DexEncodedMethod> doubleInlineeCandidates = new HashMap<>();

  public Inliner(AppInfoWithSubtyping appInfo, GraphLense graphLense, InternalOptions options) {
    this.appInfo = appInfo;
    this.graphLense = graphLense;
    this.options = options;
  }

  private Constraint instructionAllowedForInlining(
      DexEncodedMethod method, Instruction instruction) {
    Constraint result = instruction.inliningConstraint(appInfo, method.method.holder);
    if ((result == Constraint.NEVER) && instruction.isDebugInstruction()) {
      return Constraint.ALWAYS;
    }
    return result;
  }

  public Constraint computeInliningConstraint(IRCode code, DexEncodedMethod method) {
    Constraint result = Constraint.ALWAYS;
    InstructionIterator it = code.instructionIterator();
    while (it.hasNext()) {
      Instruction instruction = it.next();
      Constraint state = instructionAllowedForInlining(method, instruction);
      if (state == Constraint.NEVER) {
        return Constraint.NEVER;
      }
      if (state.ordinal() < result.ordinal()) {
        result = state;
      }
    }
    return result;
  }

  boolean hasInliningAccess(DexEncodedMethod method, DexEncodedMethod target) {
    if (!isVisibleWithFlags(target.method.holder, method.method.holder, target.accessFlags)) {
      return false;
    }
    // The class needs also to be visible for us to have access.
    DexClass targetClass = appInfo.definitionFor(target.method.holder);
    return isVisibleWithFlags(target.method.holder, method.method.holder, targetClass.accessFlags);
  }

  private boolean isVisibleWithFlags(DexType target, DexType context, AccessFlags flags) {
    if (flags.isPublic()) {
      return true;
    }
    if (flags.isPrivate()) {
      return target == context;
    }
    if (flags.isProtected()) {
      return context.isSubtypeOf(target, appInfo) || target.isSamePackage(context);
    }
    // package-private
    return target.isSamePackage(context);
  }

  synchronized DexEncodedMethod doubleInlining(DexEncodedMethod method,
      DexEncodedMethod target) {
    if (!applyDoubleInlining) {
      if (doubleInlineeCandidates.containsKey(target)) {
        // Both calls can be inlined.
        doubleInlineCallers.add(doubleInlineeCandidates.get(target));
        doubleInlineCallers.add(method);
        doubleInlineSelectedTargets.add(target);
      } else {
        // First call can be inlined.
        doubleInlineeCandidates.put(target, method);
      }
      // Just preparing for double inlining.
      return null;
    } else {
      // Don't perform the actual inlining if this was not selected.
      if (!doubleInlineSelectedTargets.contains(target)) {
        return null;
      }
    }
    return target;
  }

  public synchronized void processDoubleInlineCallers(IRConverter converter,
      OptimizationFeedback feedback) throws ApiLevelException {
    if (doubleInlineCallers.size() > 0) {
      applyDoubleInlining = true;
      List<DexEncodedMethod> methods = doubleInlineCallers
          .stream()
          .sorted(DexEncodedMethod::slowCompare)
          .collect(Collectors.toList());
      for (DexEncodedMethod method : methods) {
        converter.processMethod(method, feedback, x -> false, CallSiteInformation.empty(),
            Outliner::noProcessing);
        assert method.isProcessed();
      }
    }
  }

  /**
   * Encodes the constraints for inlining a method's instructions into a different context.
   * <p>
   * This only takes the instructions into account and not whether a method should be inlined
   * or what reason for inlining it might have. Also, it does not take the visibility of the
   * method itself into account.
   */
  public enum Constraint {
    // The ordinal values are important so please do not reorder.
    NEVER,     // Never inline this.
    SAMECLASS, // Only inline this into methods with same holder.
    PACKAGE,   // Only inline this into methods with holders from same package.
    SUBCLASS,  // Only inline this into methods with holders from a subclass.
    ALWAYS;    // No restrictions for inlining this.

    static {
      assert NEVER.ordinal() < SAMECLASS.ordinal();
      assert SAMECLASS.ordinal() < PACKAGE.ordinal();
      assert PACKAGE.ordinal() < SUBCLASS.ordinal();
      assert SUBCLASS.ordinal() < ALWAYS.ordinal();
    }

    public static Constraint deriveConstraint(
        DexType contextHolder,
        DexType targetHolder,
        AccessFlags flags,
        AppInfoWithSubtyping appInfo) {
      if (flags.isPublic()) {
        return ALWAYS;
      } else if (flags.isPrivate()) {
        return targetHolder == contextHolder ? SAMECLASS : NEVER;
      } else if (flags.isProtected()) {
        if (targetHolder.isSamePackage(contextHolder)) {
          // Even though protected, this is visible via the same package from the context.
          return PACKAGE;
        } else if (contextHolder.isSubtypeOf(targetHolder, appInfo)) {
          return SUBCLASS;
        }
        return NEVER;
      } else {
      /* package-private */
        return targetHolder.isSamePackage(contextHolder) ? PACKAGE : NEVER;
      }
    }

    public static Constraint classIsVisible(DexType context, DexType clazz,
        AppInfoWithSubtyping appInfo) {
      DexClass definition = appInfo.definitionFor(clazz);
      return definition == null ? NEVER
          : deriveConstraint(context, clazz, definition.accessFlags, appInfo);
    }

    public static Constraint min(Constraint one, Constraint other) {
      return one.ordinal() < other.ordinal() ? one : other;
    }
  }

  /**
   * Encodes the reason why a method should be inlined.
   * <p>
   * This is independent of determining whether a method can be inlined, except for the FORCE
   * state, that will inline a method irrespective of visibility and instruction checks.
   */
  public enum Reason {
    FORCE,         // Inlinee is marked for forced inlining (bridge method or renamed constructor).
    ALWAYS,        // Inlinee is marked for inlining due to alwaysinline directive.
    SINGLE_CALLER, // Inlinee has precisely one caller.
    DUAL_CALLER,   // Inlinee has precisely two callers.
    SIMPLE,        // Inlinee has simple code suitable for inlining.
  }

  static public class InlineAction {

    public final DexEncodedMethod target;
    public final Invoke invoke;
    final Reason reason;

    InlineAction(DexEncodedMethod target, Invoke invoke, Reason reason) {
      this.target = target;
      this.invoke = invoke;
      this.reason = reason;
    }

    boolean ignoreInstructionBudget() {
      return reason != Reason.SIMPLE;
    }

    public IRCode buildIR(ValueNumberGenerator generator, AppInfoWithSubtyping appInfo,
        GraphLense graphLense, InternalOptions options) throws ApiLevelException {
      if (target.isProcessed()) {
        assert target.getCode().isDexCode();
        return target.buildIR(options, generator);
      } else {
        // Build the IR for a yet not processed method, and perform minimal IR processing.
        IRCode code;
        if (target.getCode().isJarCode()) {
          code = target.getCode().asJarCode().buildIR(target, options, generator);
        } else {
          code = target.getCode().asDexCode().buildIR(target, options, generator);
        }
        new LensCodeRewriter(graphLense, appInfo).rewrite(code, target);
        return code;
      }
    }
  }

  private int numberOfInstructions(IRCode code) {
    int numOfInstructions = 0;
    for (BasicBlock block : code.blocks) {
      numOfInstructions += block.getInstructions().size();
    }
    return numOfInstructions;
  }

  private boolean legalConstructorInline(DexEncodedMethod method,
      InvokeMethod invoke, IRCode code) {

    // In the Java VM Specification section "4.10.2.4. Instance Initialization Methods and
    // Newly Created Objects" it says:
    //
    // Before that method invokes another instance initialization method of myClass or its direct
    // superclass on this, the only operation the method can perform on this is assigning fields
    // declared within myClass.

    // Allow inlining a constructor into a constructor of the same class, as the constructor code
    // is expected to adhere to the VM specification.
    DexType methodHolder = method.method.holder;
    boolean methodIsConstructor = method.isInstanceInitializer();
    if (methodIsConstructor && methodHolder == invoke.asInvokeMethod().getInvokedMethod().holder) {
      return true;
    }

    // We cannot invoke <init> on other values than |this| on Dalvik 4.4.4. Compute whether
    // the receiver to the call was the this value at the call-site.
    boolean receiverOfInnerCallIsThisOfOuter = invoke.asInvokeDirect().getReceiver().isThis();

    // Don't allow inlining a constructor into a non-constructor if the first use of the
    // un-initialized object is not an argument of an invoke of <init>.
    // Also, we cannot inline a constructor if it initializes final fields, as such is only allowed
    // from within a constructor of the corresponding class.
    // Lastly, we can only inline a constructor, if its own <init> call is on the method's class. If
    // we inline into a constructor, calls to super.<init> are also OK if the receiver of the
    // super.<init> call is the this argument.
    InstructionIterator iterator = code.instructionIterator();
    Instruction instruction = iterator.next();
    // A constructor always has the un-initialized object as the first argument.
    assert instruction.isArgument();
    Value unInitializedObject = instruction.outValue();
    boolean seenSuperInvoke = false;
    while (iterator.hasNext()) {
      instruction = iterator.next();
      if (instruction.inValues().contains(unInitializedObject)) {
        if (instruction.isInvokeDirect() && !seenSuperInvoke) {
          DexMethod target = instruction.asInvokeDirect().getInvokedMethod();
          seenSuperInvoke = appInfo.dexItemFactory.isConstructor(target);
          boolean callOnSupertypeOfThisInConstructor =
              methodHolder.isImmediateSubtypeOf(target.holder)
                  && instruction.asInvokeDirect().getReceiver() == unInitializedObject
                  && receiverOfInnerCallIsThisOfOuter
                  && methodIsConstructor;
          // TODO(65355452): Calls to init on same class should be OK, but it isn't on Dalvik.
          if (seenSuperInvoke
              // If we are inlining into a constructor, calls to superclass init are OK on the
              // |this| value in the outer context.
              && !callOnSupertypeOfThisInConstructor) {
            return false;
          }
        }
        if (!seenSuperInvoke) {
          return false;
        }
      }
      if (instruction.isInstancePut()) {
        // Fields may not be initialized outside of a constructor.
        if (!methodIsConstructor) {
          return false;
        }
        DexField field = instruction.asInstancePut().getField();
        DexEncodedField target = appInfo.lookupInstanceTarget(field.getHolder(), field);
        if (target != null && target.accessFlags.isFinal()) {
          return false;
        }
      }
    }
    return true;
  }

  public void performInlining(DexEncodedMethod method, IRCode code,
      Predicate<DexEncodedMethod> isProcessedConcurrently,
      CallSiteInformation callSiteInformation)
      throws ApiLevelException {
    int instruction_allowance = 1500;
    instruction_allowance -= numberOfInstructions(code);
    if (instruction_allowance < 0) {
      return;
    }
    computeReceiverMustBeNonNull(code);
    InliningOracle oracle = new InliningOracle(this, method, callSiteInformation,
        isProcessedConcurrently);

    List<BasicBlock> blocksToRemove = new ArrayList<>();
    ListIterator<BasicBlock> blockIterator = code.listIterator();
    while (blockIterator.hasNext() && (instruction_allowance >= 0)) {
      BasicBlock block = blockIterator.next();
      if (blocksToRemove.contains(block)) {
        continue;
      }
      InstructionListIterator iterator = block.listIterator();
      while (iterator.hasNext() && (instruction_allowance >= 0)) {
        Instruction current = iterator.next();
        if (current.isInvokeMethod()) {
          InvokeMethod invoke = current.asInvokeMethod();
          InlineAction result = invoke.computeInlining(oracle);
          if (result != null) {
            DexEncodedMethod target = result.target;
            IRCode inlinee = result
                .buildIR(code.valueNumberGenerator, appInfo, graphLense, options);
            if (inlinee != null) {
              // TODO(64432527): Get rid of this additional check by improved inlining.
              if (block.hasCatchHandlers() && inlinee.computeNormalExitBlocks().isEmpty()) {
                continue;
              }
              // If this code did not go through the full pipeline, apply inlining to make sure
              // that force inline targets get processed.
              if (!target.isProcessed()) {
                assert result.reason == Reason.FORCE;
                if (Log.ENABLED) {
                  Log.verbose(getClass(), "Forcing extra inline on " + target.toSourceString());
                }
                performInlining(target, inlinee, isProcessedConcurrently,
                    callSiteInformation);
              }
              // Make sure constructor inlining is legal.
              assert !target.isClassInitializer();
              if (target.isInstanceInitializer()
                  && !legalConstructorInline(method, invoke, inlinee)) {
                continue;
              }
              DexType downcast = null;
              if (invoke.isInvokeMethodWithReceiver()) {
                // If the invoke has a receiver but the declared method holder is different
                // from the computed target holder, inlining requires a downcast of the receiver.
                if (target.method.getHolder() != invoke.getInvokedMethod().getHolder()) {
                  downcast = result.target.method.getHolder();
                }
              }
              // Inline the inlinee code in place of the invoke instruction
              // Back up before the invoke instruction.
              iterator.previous();
              instruction_allowance -= numberOfInstructions(inlinee);
              if (instruction_allowance >= 0 || result.ignoreInstructionBudget()) {
                iterator.inlineInvoke(code, inlinee, blockIterator, blocksToRemove, downcast);
              }
              // If we inlined the invoke from a bridge method, it is no longer a bridge method.
              if (method.accessFlags.isBridge()) {
                method.accessFlags.unsetSynthetic();
                method.accessFlags.unsetBridge();
              }
            }
          }
        }
      }
    }
    oracle.finish();
    code.removeBlocks(blocksToRemove);
    assert code.isConsistentSSA();
  }

  // Determine whether the receiver of an invocation is guaranteed to be non-null based on
  // the dominator tree. If a method call is dominated by another method call with the same
  // receiver, the receiver most be non-null when we reach the dominated call.
  //
  // We bail out for exception handling. If an invoke is covered by a try block we cannot use
  // dominance to determine that the receiver is non-null at a dominated call:
  //
  // Object o;
  // try {
  //   o.m();
  // } catch (NullPointerException e) {
  //   o.f();  // Dominated by other call with receiver o, but o is null.
  // }
  //
  private void computeReceiverMustBeNonNull(IRCode code) {
    DominatorTree dominatorTree = new DominatorTree(code);
    InstructionIterator it = code.instructionIterator();
    while (it.hasNext()) {
      Instruction instruction = it.next();
      if (instruction.isInvokeMethodWithReceiver()) {
        Value receiverValue = instruction.inValues().get(0);
        for (Instruction user : receiverValue.uniqueUsers()) {
          if (user.isInvokeMethodWithReceiver() &&
              user.inValues().get(0) == receiverValue &&
              !user.getBlock().hasCatchHandlers() &&
              dominatorTree.strictlyDominatedBy(instruction.getBlock(), user.getBlock())) {
            instruction.asInvokeMethodWithReceiver().setIsDominatedByCallWithSameReceiver();
            break;
          }
        }
      }
    }
  }
}
