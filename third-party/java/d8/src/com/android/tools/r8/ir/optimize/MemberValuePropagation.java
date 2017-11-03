// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.optimize;

import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItem;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.ConstNumber;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.InstancePut;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionIterator;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.StaticGet;
import com.android.tools.r8.ir.code.StaticPut;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.android.tools.r8.shaking.ProguardMemberRule;

public class MemberValuePropagation {

  private final AppInfo appInfo;
  private final AppInfoWithLiveness liveSet;

  private enum RuleType {
    NONE,
    ASSUME_NO_SIDE_EFFECTS,
    ASSUME_VALUES
  }

  private static class ProguardMemberRuleLookup {

    final RuleType type;
    final ProguardMemberRule rule;

    ProguardMemberRuleLookup(RuleType type, ProguardMemberRule rule) {
      this.type = type;
      this.rule = rule;
    }
  }

  public MemberValuePropagation(AppInfo appInfo) {
    this.appInfo = appInfo;
    this.liveSet = appInfo.withLiveness();
  }

  private ProguardMemberRuleLookup lookupMemberRule(DexItem item) {
    if (liveSet == null) {
      return null;
    }
    ProguardMemberRule rule = liveSet.noSideEffects.get(item);
    if (rule != null) {
      return new ProguardMemberRuleLookup(RuleType.ASSUME_NO_SIDE_EFFECTS, rule);
    }
    rule = liveSet.assumedValues.get(item);
    if (rule != null) {
      return new ProguardMemberRuleLookup(RuleType.ASSUME_VALUES, rule);
    }
    return null;
  }

  private Instruction constantReplacementFromProguardRule(
      ProguardMemberRule rule, IRCode code, Instruction instruction) {
    // Check if this value can be assumed constant.
    Instruction replacement = null;
    ValueType valueType = instruction.outValue().outType();
    if (rule != null && rule.hasReturnValue() && rule.getReturnValue().isSingleValue()) {
      assert valueType != ValueType.OBJECT;
      Value value = code.createValue(valueType, instruction.getLocalInfo());
      replacement = new ConstNumber(value, rule.getReturnValue().getSingleValue());
    }
    if (replacement == null &&
        rule != null && rule.hasReturnValue() && rule.getReturnValue().isField()) {
      DexField field = rule.getReturnValue().getField();
      assert ValueType.fromDexType(field.type) == valueType;
      DexEncodedField staticField = appInfo.lookupStaticTarget(field.clazz, field);
      if (staticField != null) {
        Value value = code.createValue(valueType, instruction.getLocalInfo());
        replacement = staticField.staticValue.asConstInstruction(false, value);
      } else {
        throw new CompilationError(field.clazz.toSourceString() + "." + field.name.toString() +
            " used in assumevalues rule does not exist.");
      }
    }
    return replacement;
  }

  private void setValueRangeFromProguardRule(ProguardMemberRule rule, Value value) {
    if (rule.hasReturnValue() && rule.getReturnValue().isValueRange()) {
      assert !rule.getReturnValue().isSingleValue();
      value.setValueRange(rule.getReturnValue().getValueRange());
    }
  }

  private void replaceInstructionFromProguardRule(RuleType ruleType, InstructionIterator iterator,
      Instruction current, Instruction replacement) {
    if (ruleType == RuleType.ASSUME_NO_SIDE_EFFECTS) {
      iterator.replaceCurrentInstruction(replacement);
    } else {
      if (current.outValue() != null) {
        assert replacement.outValue() != null;
        current.outValue().replaceUsers(replacement.outValue());
      }
      replacement.setPosition(current.getPosition());
      iterator.add(replacement);
    }
  }

  /**
   * Replace invoke targets and field accesses with constant values where possible.
   * <p>
   * Also assigns value ranges to values where possible.
   */
  public void rewriteWithConstantValues(IRCode code) {
    InstructionIterator iterator = code.instructionIterator();
    while (iterator.hasNext()) {
      Instruction current = iterator.next();
      if (current.isInvokeMethod()) {
        InvokeMethod invoke = current.asInvokeMethod();
        DexMethod invokedMethod = invoke.getInvokedMethod();
        DexType invokedHolder = invokedMethod.getHolder();
        if (!invokedHolder.isClassType()) {
          continue;
        }
        DexEncodedMethod definition = appInfo.lookup(invoke.getType(), invokedMethod);

        // Process invokes marked as having no side effects.
        boolean invokeReplaced = false;
        ProguardMemberRuleLookup lookup = lookupMemberRule(definition);
        if (lookup != null) {
          if (lookup.type == RuleType.ASSUME_NO_SIDE_EFFECTS
              && (invoke.outValue() == null || !invoke.outValue().isUsed())) {
            iterator.remove();
            invokeReplaced = true;
          } else if (invoke.outValue() != null && invoke.outValue().isUsed()) {
            // Check to see if a constant value can be assumed.
            Instruction replacement =
                constantReplacementFromProguardRule(lookup.rule, code, invoke);
            if (replacement != null) {
              replaceInstructionFromProguardRule(lookup.type, iterator, current, replacement);
              invokeReplaced = true;
            } else {
              // Check to see if a value range can be assumed.
              setValueRangeFromProguardRule(lookup.rule, current.outValue());
            }
          }
        }

        // If no Proguard rule could replace the instruction check for knowledge about the
        // return value.
        if (!invokeReplaced && liveSet != null && invoke.outValue() != null) {
          DexEncodedMethod target = invoke.computeSingleTarget(liveSet);
          if (target != null) {
            if (target.getOptimizationInfo().neverReturnsNull()) {
              invoke.outValue().markNeverNull();
            }
            if (target.getOptimizationInfo().returnsConstant()) {
              long constant = target.getOptimizationInfo().getReturnedConstant();
              ValueType valueType = invoke.outType();
              Value value = code.createValue(valueType);
              Instruction knownConstReturn = new ConstNumber(value, constant);
              invoke.outValue().replaceUsers(value);
              knownConstReturn.setPosition(invoke.getPosition());
              iterator.add(knownConstReturn);
            }
          }
        }
      } else if (current.isInstancePut()) {
        InstancePut instancePut = current.asInstancePut();
        DexField field = instancePut.getField();
        DexEncodedField target = appInfo.lookupInstanceTarget(field.getHolder(), field);
        if (target != null) {
          // Remove writes to dead (i.e. never read) fields.
          if (!isFieldRead(target, false) && instancePut.object().isNeverNull()) {
            iterator.remove();
          }
        }
      } else if (current.isStaticGet()) {
        StaticGet staticGet = current.asStaticGet();
        DexField field = staticGet.getField();
        Instruction replacement = null;
        DexEncodedField target = appInfo.lookupStaticTarget(field.getHolder(), field);
        ProguardMemberRuleLookup lookup = null;
        if (target != null) {
          // Check if a this value is known const.
          replacement = target.valueAsConstInstruction(appInfo, staticGet.dest());
          if (replacement == null) {
            lookup = lookupMemberRule(target);
            if (lookup != null) {
              replacement = constantReplacementFromProguardRule(lookup.rule, code, staticGet);
            }
          }
          if (replacement == null) {
            // If no const replacement was found, at least store the range information.
            if (lookup != null) {
              setValueRangeFromProguardRule(lookup.rule, staticGet.dest());
            }
          }
          if (replacement != null) {
            // Ignore assumenosideeffects for fields.
            if (lookup != null && lookup.type == RuleType.ASSUME_VALUES) {
              replaceInstructionFromProguardRule(lookup.type, iterator, current, replacement);
            } else {
              iterator.replaceCurrentInstruction(replacement);
            }
          }
        }
      } else if (current.isStaticPut()) {
        StaticPut staticPut = current.asStaticPut();
        DexField field = staticPut.getField();
        DexEncodedField target = appInfo.lookupStaticTarget(field.getHolder(), field);
        if (target != null) {
          // Remove writes to dead (i.e. never read) fields.
          if (!isFieldRead(target, true)) {
            iterator.remove();
          }
        }
      }
    }
    assert code.isConsistentSSA();
  }

  private boolean isFieldRead(DexEncodedField field, boolean isStatic) {
    // Without live set information we cannot tell and assume true.
    if (liveSet == null
        || liveSet.fieldsRead.contains(field.field)
        || liveSet.isPinned(field.field)) {
      return true;
    }
    // For library classes we don't know whether a field is read.
    DexClass holder = appInfo.definitionFor(field.field.clazz);
    return holder == null || holder.isLibraryClass();
  }
}
