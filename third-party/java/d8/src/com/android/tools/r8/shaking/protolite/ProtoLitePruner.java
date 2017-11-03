// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking.protolite;

import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.DominatorTree;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.InstanceGet;
import com.android.tools.r8.ir.code.InstancePut;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionIterator;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.InvokeInterface;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.android.tools.r8.ir.code.InvokeStatic;
import com.android.tools.r8.ir.code.MemberType;
import com.android.tools.r8.ir.code.Switch;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.ir.optimize.SwitchUtils;
import com.android.tools.r8.ir.optimize.SwitchUtils.EnumSwitchInfo;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Implements the second phase of proto lite tree shaking.
 * <p>
 * In the pruner pass, we remove all references to the now dead fields from the methods that
 * were treated specially during tree shaking using the {@link ProtoLiteExtension}.
 * <p>
 * For proto2 code, we aim to keep presence information for fields alive and move the values of
 * dead fields to the unknown fields storage. For proto3, as it has no concept of passing on
 * unknown fields, dead fields are completely removed. In particular, reading a proto and writing
 * it again might loose data.
 */
public class ProtoLitePruner extends ProtoLiteBase {

  private final DexType visitorType;

  private final DexType methodEnumType;
  private final DexType codedOutputStreamType;
  private final DexType protobufListType;
  private final DexType listType;

  private final DexString visitTag;
  private final DexString mergeTag;
  private final DexString isInitializedTag;
  private final DexString makeImmutabkeTag;
  private final DexString computeMethodPrefix;
  private final DexString writeMethodPrefix;
  private final DexString isInitializedMethodName;

  private final DexString makeImmutableMethodName;
  private final DexString sizeMethodName;
  private final Set<DexField> seenFields;
  private final DexMethod sizeMethod;

  public ProtoLitePruner(AppInfoWithLiveness appInfo) {
    super(appInfo);
    DexItemFactory factory = appInfo.dexItemFactory;
    this.visitorType = factory.createType("Lcom/google/protobuf/GeneratedMessageLite$Visitor;");
    this.methodEnumType = factory
        .createType("Lcom/google/protobuf/GeneratedMessageLite$MethodToInvoke;");
    this.codedOutputStreamType = factory.createType("Lcom/google/protobuf/CodedOutputStream;");
    this.protobufListType = factory.createType("Lcom/google/protobuf/Internal$ProtobufList;");
    this.listType = factory.createType("Ljava/util/List;");
    this.visitTag = factory.createString("VISIT");
    this.mergeTag = factory.createString("MERGE_FROM_STREAM");
    this.isInitializedTag = factory.createString("IS_INITIALIZED");
    this.makeImmutabkeTag = factory.createString("MAKE_IMMUTABLE");
    this.computeMethodPrefix = factory.createString("compute");
    this.writeMethodPrefix = factory.createString("write");
    this.sizeMethodName = factory.createString("size");
    this.isInitializedMethodName = factory.createString("isInitialized");
    this.makeImmutableMethodName = factory.createString("makeImmutable");
    this.sizeMethod = factory.createMethod(listType,
        factory.createProto(factory.intType), sizeMethodName);

    seenFields = appInfo.withLiveness()
        .getExtension(ProtoLiteExtension.class, Collections.emptySet());
  }

  private boolean isPresenceField(DexField field, DexType instanceType) {
    if (field.getHolder() != instanceType) {
      return false;
    }
    // Proto2 uses fields named bitField<n>_ fields to store presence information.
    String fieldName = field.name.toString();
    return fieldName.endsWith("_")
        && fieldName.startsWith("bitField");
  }

  @Override
  boolean isSetterThatNeedsProcessing(DexEncodedMethod method) {
    // The pruner does not need to process setters, so this method always returns false.
    return false;
  }

  private boolean isGetter(DexMethod method) {
    return isGetterHelper(method, 0);
  }

  private boolean isCountGetter(DexMethod method) {
    return isGetterHelper(method, COUNT_POSTFIX_LENGTH);
  }

  private boolean isGetterHelper(DexMethod method, int postFixLength) {
    if (!method.name.beginsWith(getterNamePrefix)
        || !(method.proto.parameters.isEmpty() || hasSingleIntArgument(method))
        || (method.holder == messageType)
        || !method.holder.isSubtypeOf(messageType, appInfo)
        || method.name.size <= GETTER_NAME_PREFIX_LENGTH + postFixLength) {
      return false;
    }
    DexField correspondingField = getterToField(method, postFixLength);
    return seenFields.contains(correspondingField);
  }

  private boolean isDefinedAsNull(Value value) {
    return value.definition != null && value.isZero();
  }

  private boolean isComputeSizeMethod(DexMethod invokedMethod) {
    return invokedMethod.holder == codedOutputStreamType
        && invokedMethod.name.beginsWith(computeMethodPrefix);
  }

  private boolean isWriteMethod(DexMethod invokedMethod) {
    return invokedMethod.holder == codedOutputStreamType
        && invokedMethod.name.beginsWith(writeMethodPrefix);
  }

  private boolean isProtoField(DexField field) {
    return seenFields.contains(field);
  }

  public void rewriteProtoLiteSpecialMethod(IRCode code, DexEncodedMethod method) {
    DexString methodName = method.method.name;
    if (methodName == dynamicMethodName) {
      rewriteDynamicMethod(code, method);
    } else if ((methodName == writeToMethodName) || (methodName == getSerializedSizeMethodName)) {
      rewriteSizeOrWriteMethod(code);
    } else if (methodName == constructorMethodName) {
      rewriteConstructor(code);
    } else {
      throw new Unreachable();
    }
  }

  /**
   * For protos with repeated fields, the constructor may contain field initialization code like
   *
   * <pre>
   * private Repeated() {
   *  repeated_ = com.google.protobuf.GeneratedMessageLite.emptyProtobufList();
   *  other_ = emptyBooleanList();
   *  sub_ = emptyProtobufList();
   * }
   * </pre>
   *
   * which this rewriting removes.
   */
  private void rewriteConstructor(IRCode code) {
    boolean wasRewritten;
    do {
      wasRewritten = false;
      InstructionIterator it = code.instructionIterator();
      while (it.hasNext()) {
        Instruction insn = it.next();
        if (insn.isInstancePut() && isDeadProtoField(insn.asInstancePut().getField())) {
          // Remove initializations of dead fields.
          it.remove();
          wasRewritten = true;
        } else if (insn.isInvokeStatic()) {
          // Remove now unneeded constructor calls.
          InvokeStatic invokeStatic = insn.asInvokeStatic();
          DexMethod invokedMethod = invokeStatic.getInvokedMethod();
          if ((!invokeStatic.outValue().isUsed())
              && invokedMethod.proto.returnType.isSubtypeOf(protobufListType, appInfo)) {
            it.remove();
          }
        }
      }
    } while (wasRewritten);
  }


  /**
   * The writeTo and getSerializedSize methods of a generated proto access all fields. We have to
   * remove the accesses to dead fields. The actual code of these methods varies to quite some
   * degree depending on the types of the fields. For example
   *
   * <pre>
   * public void writeTo(com.google.protobuf.CodedOutputStream output) throws java.io.IOException {
   *   if (((bitField0_ & 0x00000001) == 0x00000001)) {
   *     output.writeInt32(1, id_);
   *   }
   *   if (((bitField0_ & 0x00000002) == 0x00000002)) {
   *     output.writeMessage(2, getInner());
   *   }
   *   for (int i = 0; i < repeated_.size(); i++) {
   *     output.writeString(3, repeated_.get(i));
   *   }
   *   for (int i = 0; i < other_.size(); i++) {
   *     output.writeBool(4, other_.getBoolean(i));
   *   }
   *   for (int i = 0; i < sub_.size(); i++) {
   *     output.writeMessage(5, sub_.get(i));
   *   }
   *   unknownFields.writeTo(output);
   * }
   * </pre>
   *
   * We look for direct field accesses (id_, repeated_, etc. above) and getters (getInner) and
   * rewrite those to null/0. We also rewrite all uses of the results, like the size() and
   * write methods above.
   */
  private void rewriteSizeOrWriteMethod(IRCode code) {
    boolean wasRewritten;
    do {
      wasRewritten = false;
      InstructionIterator it = code.instructionIterator();
      while (it.hasNext()) {
        Instruction insn = it.next();
        if (insn.isInstanceGet()) {
          DexField field = insn.asInstanceGet().getField();
          if (isDeadProtoField(field)) {
            // Rewrite deads field access to corresponding 0.
            it.replaceCurrentInstruction(code.createConstNull(insn.asInstanceGet()));
            wasRewritten = true;
          }
        } else if (insn.isInvokeMethodWithReceiver()) {
          InvokeMethodWithReceiver invokeMethod = insn.asInvokeMethodWithReceiver();
          DexMethod invokedMethod = invokeMethod.getInvokedMethod();
          if (isDeadProtoGetter(invokedMethod)) {
            // Rewrite dead getters.
            it.replaceCurrentInstruction(code.createConstNull(invokeMethod));
            wasRewritten = true;
          } else if (invokedMethod.name == sizeMethodName
              && invokedMethod.holder.isSubtypeOf(listType, appInfo)) {
            Value receiver = invokeMethod.getReceiver();
            if (isDefinedAsNull(receiver)) {
              // Rewrite size() methods with null receiver.
              it.replaceCurrentInstruction(code.createConstNull(invokeMethod));
            }
          } else if (invokedMethod.name == getterNamePrefix
              && invokedMethod.holder.isSubtypeOf(listType, appInfo)) {
            Value receiver = invokeMethod.getReceiver();
            if (isDefinedAsNull(receiver)) {
              // Rewrite get(x) methods with null receiver.
              it.replaceCurrentInstruction(code.createConstNull(invokeMethod));
              wasRewritten = true;
            }
          } else if (isWriteMethod(invokedMethod)) {
            Value lastArg = Iterables.getLast(invokeMethod.inValues());
            if (isDefinedAsNull(lastArg)) {
              it.remove();
            }
          }
        } else if (insn.isInvokeMethod()) {
          InvokeMethod invokeMethod = insn.asInvokeMethod();
          DexMethod invokedMethod = invokeMethod.getInvokedMethod();
          if (isComputeSizeMethod(invokedMethod)) {
            Value lastArg = Iterables.getLast(invokeMethod.inValues());
            if (isDefinedAsNull(lastArg)) {
              // This is a computeSize method on a constant null. The field was dead.
              assert (invokeMethod.outValue() != null);
              it.replaceCurrentInstruction(code.createConstNull(invokeMethod));
              wasRewritten = true;
            }
          }
        }
      }
    } while (wasRewritten);
  }

  /**
   * The dyanmicMethod code is actually a collection of various methods contained in one.
   * It uses a switch statement over an enum to identify which actual operation is performed.
   * We need to rewrite all cases that might access dead fields. These are
   * <dl>
   * <dt>IS_INITIALIZED</dt>
   * <dd>See {@link #rewriteIsInitializedCase}.</dd>
   * <dt>MAKE_IMMUTABLE</dt>
   * <dd>See {@link #rewriteMakeImmutableCase}.</dd>
   * <dt>VISIT</dt>
   * <dd>See {@link #rewriteVisitCase}.</dd>
   * <dt>MERGE_FROM_STREAM</dt>
   * <dd>See {@link #rewriteMergeCase}.</dd>
   * </dl>
   */
  private void rewriteDynamicMethod(IRCode code, DexEncodedMethod method) {
    // This method contains a switch and we are interested in some of the cases only.
    InstructionIterator iterator = code.instructionIterator();
    Instruction matchingInstr = iterator.nextUntil(Instruction::isSwitch);
    if (matchingInstr == null) {
      throw new CompilationError("dynamicMethod in protoLite without switch.");
    }
    Switch switchInstr = matchingInstr.asSwitch();
    EnumSwitchInfo info = SwitchUtils.analyzeSwitchOverEnum(switchInstr, appInfo.withLiveness());
    if (info == null || info.enumClass != methodEnumType) {
      throw new CompilationError("Malformed switch in dynamicMethod of proto lite.");
    }
    BasicBlock initializedCase = null;
    BasicBlock visitCase = null;
    BasicBlock mergeCase = null;
    BasicBlock makeImmutableCase = null;
    for (int keyIdx = 0; keyIdx < switchInstr.numberOfKeys(); keyIdx++) {
      int key = switchInstr.getKey(keyIdx);
      DexField label = info.indexMap.get(key);
      assert label != null;
      if (label.name == visitTag) {
        assert visitCase == null;
        visitCase = switchInstr.targetBlock(keyIdx);
      } else if (label.name == mergeTag) {
        assert mergeCase == null;
        mergeCase = switchInstr.targetBlock(keyIdx);
      } else if (label.name == isInitializedTag) {
        assert initializedCase == null;
        initializedCase = switchInstr.targetBlock(keyIdx);
      } else if (label.name == makeImmutabkeTag) {
        assert makeImmutableCase == null;
        makeImmutableCase = switchInstr.targetBlock(keyIdx);
      }
    }
    DexType instanceType = method.method.getHolder();
    rewriteIsInitializedCase(initializedCase, instanceType, code);
    assert code.isConsistentSSA();
    rewriteMakeImmutableCase(makeImmutableCase, code);
    assert code.isConsistentSSA();
    rewriteVisitCase(visitCase, code);
    assert code.isConsistentSSA();
    rewriteMergeCase(mergeCase, instanceType, code);
    // Rewriting of the merge case changes branching structure and adds more blocks that target
    // the fallthrough label. This can introduce critical edges. Therefore, we split critical
    // edges to maintain our edge-split form.
    code.splitCriticalEdges();
    code.traceBlocks();
    assert code.isConsistentSSA();
  }

  /**
   * In the presence of repeated fields, the MAKE_IMMUTABLE case will contain code of the form
   *
   * <pre>
   * case MAKE_IMMUTABLE: {
   *   repeated_.makeImmutable();
   *   other_.makeImmutable();
   *   sub_.makeImmutable();
   *   return null;
   * }
   * </pre>
   *
   * For dead fields, we remove the field access and the call to makeImmutable.
   */
  private void rewriteMakeImmutableCase(BasicBlock switchCase, IRCode code) {
    DominatorTree dom = new DominatorTree(code);
    boolean wasRewritten;
    do {
      wasRewritten = false;
      for (BasicBlock current : dom.dominatedBlocks(switchCase)) {
        InstructionIterator it = current.iterator();
        while (it.hasNext()) {
          Instruction insn = it.next();
          if (insn.isInstanceGet() && isDeadProtoField(insn.asInstanceGet().getField())) {
            it.replaceCurrentInstruction(code.createConstNull(insn));
            wasRewritten = true;
          } else if (insn.isInvokeMethodWithReceiver()) {
            InvokeMethodWithReceiver invokeMethod = insn.asInvokeMethodWithReceiver();
            DexMethod invokedMethod = invokeMethod.getInvokedMethod();
            if (isDeadProtoGetter(invokedMethod)) {
              it.replaceCurrentInstruction(code.createConstNull(invokeMethod));
              wasRewritten = true;
            } else if (invokedMethod.name == makeImmutableMethodName
                && invokedMethod.getHolder().isSubtypeOf(protobufListType, appInfo)) {
              Value receiver = invokeMethod.getReceiver();
              if (isDefinedAsNull(receiver)) {
                it.remove();
              }
            }
          }
        }
      }
    } while (wasRewritten);
  }

  /**
   * The IS_INITIALIZED case also has a high degree of variability depending on the type of fields.
   * Common code looks as follows
   *
   * <pre>
   * case IS_INITIALIZED: {
   *   byte isInitialized = memoizedIsInitialized;
   *   if (isInitialized == 1) return DEFAULT_INSTANCE;
   *   if (isInitialized == 0) return null;
   *
   *   boolean shouldMemoize = ((Boolean) arg0).booleanValue();
   *   if (!hasId()) {
   *     if (shouldMemoize) {
   *       memoizedIsInitialized = 0;
   *     }
   *     return null;
   *   }
   *   for (int i = 0; i < getSubCount(); i++) {
   *     if (!getSub(i).isInitialized()) {
   *       if (shouldMemoize) {
   *         memoizedIsInitialized = 0;
   *       }
   *       return null;
   *     }
   *   }
   *   if (shouldMemoize) memoizedIsInitialized = 1;
   *   return DEFAULT_INSTANCE;
   * }
   * </pre>
   *
   * We remove all the accesses of dead fields and getters. We also replace secondary invokes
   * (like getSubCount or isInitialized) with conservative default values (e.g. 0, true).
   * <p>
   * We also rewrite getXXXCount methods on live fields, as accesses to those methods was filtered
   * during tree shaking. In place of those methods, we inline their definition.
   */
  private void rewriteIsInitializedCase(BasicBlock switchCase, DexType instanceType,
      IRCode code) {
    DominatorTree dom = new DominatorTree(code);
    boolean wasRewritten;
    do {
      wasRewritten = false;
      for (BasicBlock current : dom.dominatedBlocks(switchCase)) {
        InstructionIterator it = current.iterator();
        while (it.hasNext()) {
          Instruction insn = it.next();
          if (insn.isInvokeMethodWithReceiver()) {
            InvokeMethodWithReceiver invokeMethod = insn.asInvokeMethodWithReceiver();
            DexMethod invokedMethod = invokeMethod.getInvokedMethod();
            if (isDeadProtoGetter(invokedMethod)) {
              it.replaceCurrentInstruction(code.createConstNull(invokeMethod));
              wasRewritten = true;
            } else if (invokedMethod.name == isInitializedMethodName
                && invokedMethod.getHolder().isSubtypeOf(messageType, appInfo)) {
              Value receiver = invokeMethod.getReceiver();
              if (isDefinedAsNull(receiver)) {
                // We cannot compute initialization state for nested messages and repeated
                // messages that have been removed or moved to unknown fields. Just return
                // true.
                it.replaceCurrentInstruction(code.createTrue());
                wasRewritten = true;
              }
            } else if (isCountGetter(invokedMethod)) {
              // We have to rewrite these as a precaution, as they might be dead due to
              // tree shaking ignoring them.
              DexField field = getterToField(invokedMethod, 5);
              if (appInfo.withLiveness().liveFields.contains(field)) {
                // Effectively inline the code that is normally inside these methods.
                Value thisReference = invokeMethod.getReceiver();
                Value newResult = code.createValue(ValueType.INT);
                invokeMethod.outValue().replaceUsers(newResult);
                Value theList = code.createValue(ValueType.OBJECT);
                it.replaceCurrentInstruction(
                    new InstanceGet(MemberType.OBJECT, theList, thisReference, field));
                it.add(new InvokeInterface(sizeMethod, newResult, Collections.emptyList()));
              } else {
                // The field is dead, so its count is always 0.
                it.replaceCurrentInstruction(code.createConstNull(invokeMethod));
              }
            }
          }
        }
      }
    } while (wasRewritten);
  }

  private InstancePut findProtoFieldWrite(BasicBlock block, DexType instanceType,
      BiPredicate<DexField, DexType> filter, DominatorTree dom) {
    for (BasicBlock current : dom.dominatedBlocks(block)) {
      InstructionIterator insns = current.iterator();
      InstancePut instancePut = (InstancePut) insns.nextUntil(Instruction::isInstancePut);
      if (instancePut != null && filter.test(instancePut.getField(), instanceType)) {
        return instancePut;
      }
    }
    return null;
  }

  /**
   * For the merge case, we typically see code that contains a switch over the field tags. The inner
   * switch has the form
   *
   * <pre>
   * switch (tag) {
   *   case 0:
   *     done = true;
   *     break;
   *   default: {
   *     if (!parseUnknownField(tag, input)) {
   *       done = true;
   *     }
   *     break;
   *   }
   *   case 8: {
   *     bitField0_ |= 0x00000001;
   *     id_ = input.readInt32();
   *     break;
   *   }
   *   case 18: {
   *     nestedproto2.GeneratedNestedProto.NestedOne.Builder subBuilder = null;
   *     if (((bitField0_ & 0x00000002) == 0x00000002)) {
   *       subBuilder = inner_.toBuilder();
   *     }
   *     inner_ = input.readMessage(nestedproto2.GeneratedNestedProto.NestedOne.parser(),
   *         extensionRegistry);
   *     if (subBuilder != null) {
   *       subBuilder.mergeFrom(inner_);
   *       inner_ = subBuilder.buildPartial();
   *     }
   *     bitField0_ |= 0x00000002;
   *     break;
   *   }
   *   case 24: {
   *     if (!other_.isModifiable()) {
   *       other_ =
   *           com.google.protobuf.GeneratedMessageLite.mutableCopy(other_);
   *     }
   *     other_.addBoolean(input.readBool());
   *     break;
   *   }
   *   case 26: {
   *     int length = input.readRawVarint32();
   *     int limit = input.pushLimit(length);
   *     if (!other_.isModifiable() && input.getBytesUntilLimit() > 0) {
   *       final int currentSize = other_.size();
   *       other_ = other_.mutableCopyWithCapacity(
   *       currentSize + (length/1));
   *     }
   *     while (input.getBytesUntilLimit() > 0) {
   *       other_.addBoolean(input.readBool());
   *     }
   *     input.popLimit(limit);
   *     break;
   *   }
   * }
   * </pre>
   *
   * The general approach here is to identify the field that is processed by a case and, if
   * the field is dead, remove the entire case.
   * <p>
   * We slightly complicate the rewriting by also checking whether the block computes a
   * presence bitfield (bitField0_ above). If so, we move that computation to a new block that
   * continues to the default case. This ensures that presence is recorded correctly, yet the
   * field is moved to the unknownFields collection, if such exists.
   */
  private void rewriteMergeCase(BasicBlock caseBlock, DexType instanceType,
      IRCode code) {
    // We are looking for a switch statement over the input tag. Just traverse all blocks until
    // we find it.
    List<BasicBlock> deadBlocks = new ArrayList<>();
    DominatorTree dom = new DominatorTree(code);
    for (BasicBlock current : dom.dominatedBlocks(caseBlock)) {
      InstructionIterator it = current.iterator();
      Switch switchInstr;
      if ((switchInstr = (Switch) it.nextUntil(Instruction::isSwitch)) != null) {
        int nextBlock = code.getHighestBlockNumber() + 1;
        IntList liveKeys = new IntArrayList(switchInstr.numberOfKeys());
        List<BasicBlock> liveBlocks = new ArrayList<>(switchInstr.numberOfKeys());
        boolean needsCleanup = false;
        // Filter out all the cases that contain writes to dead fields.
        for (int keyIdx = 0; keyIdx < switchInstr.numberOfKeys(); keyIdx++) {
          BasicBlock targetBlock = switchInstr.targetBlock(keyIdx);
          InstancePut instancePut =
              findProtoFieldWrite(targetBlock, instanceType, (field, holder) -> isProtoField(field),
                  dom);
          if (instancePut == null
              || appInfo.withLiveness().liveFields.contains(instancePut.getField())) {
            // This is a live case. Keep it.
            liveKeys.add(switchInstr.getKey(keyIdx));
            liveBlocks.add(targetBlock);
          } else {
            // We cannot just remove this entire switch case if there is some computation here
            // for whether the field is present. We check this by searching for a write to
            // the bitField<xxx>_ fields. If such write exists, we move the corresponding
            // instructions to the first block in the switch.
            //TODO(herhut): Only do this if the written field has a live hasMethod.
            InstancePut bitFieldUpdate = findProtoFieldWrite(targetBlock, instanceType,
                this::isPresenceField, dom);
            if (bitFieldUpdate != null) {
              BasicBlock newBlock = BasicBlock.createGotoBlock(nextBlock++);
              newBlock.link(switchInstr.fallthroughBlock());
              // Copy over the computation of the field;
              moveInstructionTo(newBlock.listIterator(), bitFieldUpdate, dom, targetBlock);
              switchInstr.getBlock().link(newBlock);
              liveKeys.add(switchInstr.getKey(keyIdx));
              liveBlocks.add(newBlock);
              code.blocks.add(newBlock);
            }
            needsCleanup = true;
          }
        }
        if (needsCleanup) {
          DominatorTree updatedTree = new DominatorTree(code);
          BasicBlock fallThrough = switchInstr.fallthroughBlock();
          List<BasicBlock> successors = ImmutableList.copyOf(current.getNormalSuccessors());
          for (BasicBlock successor : successors) {
            if (successor != fallThrough && !liveBlocks.contains(successor)) {
              deadBlocks.addAll(current.unlink(successor, updatedTree));
            }
          }
          int[] blockIndices = new int[liveBlocks.size()];
          for (int i = 0; i < liveBlocks.size(); i++) {
            blockIndices[i] = current.getSuccessors().indexOf(liveBlocks.get(i));
          }
          Switch newSwitch = new Switch(switchInstr.inValues().get(0), liveKeys.toIntArray(),
              blockIndices, current.getSuccessors().indexOf(fallThrough));
          it.replaceCurrentInstruction(newSwitch);
        }
        break;
      }
    }
    code.removeBlocks(deadBlocks);
  }

  //TODO(herhut): This should really be a copy with a value substitution map.
  private void moveInstructionTo(InstructionListIterator iterator, Instruction insn,
      DominatorTree dom,
      BasicBlock dominator) {
    for (Value value : insn.inValues()) {
      Instruction input = value.definition;
      // We do not support phis.
      assert input != null;
      if (dom.dominatedBy(input.getBlock(), dominator)) {
        // And no shared instructions.
        assert input.outValue().numberOfUsers() == 1;
        moveInstructionTo(iterator, input, dom, dominator);
      }
    }
    insn.getBlock().removeInstruction(insn);
    iterator.add(insn);
  }

  private boolean isDeadProtoField(DexField field) {
    return isProtoField(field) && !appInfo.withLiveness().liveFields.contains(field);
  }

  private boolean isDeadProtoGetter(DexMethod method) {
    return isGetter(method) && isDeadProtoField(getterToField(method));
  }

  private boolean isVisitOfDeadField(Instruction instruction) {
    if (!instruction.isInvokeMethod()) {
      return false;
    }
    InvokeMethod invokeMethod = instruction.asInvokeMethod();
    if (invokeMethod.getInvokedMethod().getHolder() == visitorType
        && invokeMethod.getInvokedMethod().getArity() >= 2) {
      Instruction secondArg = invokeMethod.inValues().get(2).definition;
      return secondArg.isConstNumber();
    }
    return false;
  }

  /**
   * The visit case has typically the form
   *
   * <pre>
   * case VISIT: {
   *   Visitor visitor = (Visitor) arg0;
   *   repeatedproto.GeneratedRepeatedProto.Repeated other =
   *       (repeatedproto.GeneratedRepeatedProto.Repeated) arg1;
   *   id_ = visitor.visitInt(
   *       hasId(), id_,
   *       other.hasId(), other.id_);
   *   repeated_= visitor.visitList(repeated_, other.repeated_);
   *   inner_ = visitor.visitMessage(inner_, other.inner_);
   *   if (visitor == com.google.protobuf.GeneratedMessageLite.MergeFromVisitor.INSTANCE) {
   *     bitField0_ |= other.bitField0_;
   *   }
   *   return this;
   * }
   * </pre>
   *
   * We remove all writes and reads to dead fields and correspondign secondary instructions, like
   * the visitXXX methods.
   * <p>
   * Note that the invoked hasMethods are benign, as the only access the bitFieldXXX_ fields, which
   * we currently do not remove. Inlining will likely remove the methods.
   */
  private void rewriteVisitCase(BasicBlock switchCase, IRCode code) {
    DominatorTree dom = new DominatorTree(code);
    boolean wasRewritten;
    do {
      wasRewritten = false;
      for (BasicBlock target : dom.dominatedBlocks(switchCase)) {
        InstructionIterator it = target.iterator();
        while (it.hasNext()) {
          Instruction insn = it.next();
          if (insn.isInstanceGet()) {
            InstanceGet instanceGet = insn.asInstanceGet();
            if (isDeadProtoField(instanceGet.getField())) {
              it.replaceCurrentInstruction(code.createConstNull(instanceGet));
              wasRewritten = true;
            }
          } else if (insn.isInstancePut()) {
            if (isDeadProtoField(insn.asInstancePut().getField())) {
              it.remove();
            }
          } else if (isVisitOfDeadField(insn)) {
            it.replaceCurrentInstruction(code.createConstNull(insn));
          } else if (insn.isCheckCast()) {
            // The call to visitXXX is a generic method invoke, so it will be followed by a check
            // cast to fix up the type. As the result is no longer needed once we are done, we can
            // remove the cast. This removes a potential last reference to an inner message class.
            // TODO(herhut): We should have a generic dead cast removal.
            Value inValue = insn.inValues().get(0);
            if (isDefinedAsNull(inValue)) {
              insn.outValue().replaceUsers(inValue);
              it.remove();
            }
          }

        }
      }
    } while (wasRewritten);
  }
}
