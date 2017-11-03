// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.ClassAccessFlags;
import com.android.tools.r8.graph.Code;
import com.android.tools.r8.graph.DebugLocalInfo;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexAnnotationSetRefList;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.graph.UseRegistry;
import com.android.tools.r8.ir.code.Add;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.BasicBlock.ThrowingInfo;
import com.android.tools.r8.ir.code.CatchHandlers;
import com.android.tools.r8.ir.code.Div;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.ir.code.Invoke.Type;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.InvokeStatic;
import com.android.tools.r8.ir.code.Mul;
import com.android.tools.r8.ir.code.NewInstance;
import com.android.tools.r8.ir.code.Position;
import com.android.tools.r8.ir.code.Rem;
import com.android.tools.r8.ir.code.Sub;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.ir.conversion.IRBuilder;
import com.android.tools.r8.ir.conversion.SourceCode;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.StringUtils.BraceType;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class Outliner {

  private final InternalOptions options;
  private final Map<Outline, List<DexEncodedMethod>> candidates = new HashMap<>();
  private final Map<Outline, DexMethod> generatedOutlines = new HashMap<>();
  private final Set<DexEncodedMethod> methodsSelectedForOutlining = Sets.newIdentityHashSet();

  static final int MAX_IN_SIZE = 5;  // Avoid using ranged calls for outlined code.

  final private AppInfo appInfo;
  final private DexItemFactory dexItemFactory;

  // Representation of an outline.
  // This includes the instructions in the outline, and a map from the arguments of this outline
  // to the values in the instructions.
  //
  // E.g. an outline of two StringBuilder.append(String) calls could look like this:
  //
  //  InvokeVirtual       { v5 v6 } Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
  //  InvokeVirtual       { v5 v9 } Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
  //  ReturnVoid
  //
  // It takes three arguments
  //
  //   v5, v6, v9
  //
  // and the argument map is a list mapping the arguments to the in-values of all instructions in
  // the order they are encountered, in this case:
  //
  //   [0, 1, 0, 2]
  //
  // The actual value numbers (in this example v5, v6, v9 are "arbitrary", as the instruction in
  // the outline are taken from the block where they are collected as candidates. The comparison
  // of two outlines rely on the instructions and the argument mapping *not* the concrete values.
  public class Outline implements Comparable<Outline> {

    final List<Value> arguments;
    final List<DexType> argumentTypes;
    final List<Integer> argumentMap;
    final List<Instruction> templateInstructions = new ArrayList<>();
    final public DexType returnType;

    private DexProto proto;

    // Build an outline over the instructions [start, end[.
    // The arguments are the arguments to pass to an outline of these instructions.
    Outline(List<Instruction> instructions, List<Value> arguments, List<DexType> argumentTypes,
        List<Integer> argumentMap, DexType returnType, int start, int end) {
      this.arguments = arguments;
      this.argumentTypes = argumentTypes;
      this.argumentMap = argumentMap;
      this.returnType = returnType;

      for (int i = start; i < end; i++) {
        Instruction current = instructions.get(i);
        if (current.isInvoke() || current.isNewInstance() || current.isArithmeticBinop()) {
          templateInstructions.add(current);
        } else if (current.isConstInstruction()) {
          // Don't include const instructions in the template.
        } else {
          assert false : "Unexpected type of instruction in outlining template.";
        }
      }
    }

    int argumentCount() {
      return arguments.size();
    }

    DexProto buildProto() {
      if (proto == null) {
        DexType[] argumentTypesArray = argumentTypes.toArray(new DexType[argumentTypes.size()]);
        proto = dexItemFactory.createProto(returnType, argumentTypesArray);
      }
      return proto;
    }

    // Build the DexMethod for this outline.
    DexMethod buildMethod(DexType clazz, DexString name) {
      return dexItemFactory.createMethod(clazz, buildProto(), name);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Outline)) {
        return false;
      }
      List<Instruction> instructions0 = this.templateInstructions;
      List<Instruction> instructions1 = ((Outline) other).templateInstructions;
      if (instructions0.size() != instructions1.size()) {
        return false;
      }
      for (int i = 0; i < instructions0.size(); i++) {
        Instruction i0 = instructions0.get(i);
        Instruction i1 = instructions1.get(i);
        // Note that we don't consider positions as this optimization already breaks stack traces.
        if (i0.getClass() != i1.getClass() || !i0.identicalNonValueNonPositionParts(i1)) {
          return false;
        }
        if ((i0.outValue() != null) != (i1.outValue() != null)) {
          return false;
        }
      }
      return argumentMap.equals(((Outline) other).argumentMap)
          && returnType == ((Outline) other).returnType;
    }

    @Override
    public int hashCode() {
      final int MAX_HASH_INSTRUCTIONS = 5;

      int hash = templateInstructions.size();
      int hashPart = 0;
      for (int i = 0; i < templateInstructions.size() && i < MAX_HASH_INSTRUCTIONS; i++) {
        Instruction instruction = templateInstructions.get(i);
        if (instruction.isInvokeMethod()) {
          hashPart = hashPart << 4;
          hashPart += instruction.asInvokeMethod().getInvokedMethod().hashCode();
        }
        hash = hash * 3 + hashPart;
      }
      return hash;
    }

    @Override
    public int compareTo(Outline other) {
      if (this == other) {
        return 0;
      }
      // First compare the proto.
      int result;
      result = buildProto().slowCompareTo(other.buildProto());
      if (result != 0) {
        assert !equals(other);
        return result;
      }
      assert argumentCount() == other.argumentCount();
      // Then compare the instructions (non value part).
      List<Instruction> instructions0 = templateInstructions;
      List<Instruction> instructions1 = other.templateInstructions;
      result = instructions0.size() - instructions1.size();
      if (result != 0) {
        assert !equals(other);
        return result;
      }
      for (int i = 0; i < instructions0.size(); i++) {
        Instruction i0 = instructions0.get(i);
        Instruction i1 = instructions1.get(i);
        result = i0.getInstructionName().compareTo(i1.getInstructionName());
        if (result != 0) {
          assert !equals(other);
          return result;
        }
        result = i0.inValues().size() - i1.inValues().size();
        if (result != 0) {
          assert !equals(other);
          return result;
        }
        result = (i0.outValue() != null ? 1 : 0) - (i1.outValue() != null ? 1 : 0);
        if (result != 0) {
          assert !equals(other);
          return result;
        }
        result = i0.compareNonValueParts(i1);
        if (result != 0) {
          assert !equals(other);
          return result;
        }
      }
      // Finally compare the value part.
      result = argumentMap.size() - other.argumentMap.size();
      if (result != 0) {
        assert !equals(other);
        return result;
      }
      for (int i = 0; i < argumentMap.size(); i++) {
        result = argumentMap.get(i) - other.argumentMap.get(i);
        if (result != 0) {
          assert !equals(other);
          return result;
        }
      }
      assert equals(other);
      return 0;
    }

    @Override
    public String toString() {
      // The printing of the code for an outline maps the value numbers to the arguments numbers.
      int outRegisterNumber = arguments.size();
      StringBuilder builder = new StringBuilder();
      builder.append(returnType);
      builder.append(" anOutline");
      StringUtils.append(builder, argumentTypes, ", ", BraceType.PARENS);
      builder.append("\n");
      int argumentMapIndex = 0;
      for (Instruction instruction : templateInstructions) {
        String name = instruction.getInstructionName();
        StringUtils.appendRightPadded(builder, name, 20);
        if (instruction.outValue() != null) {
          builder.append("v" + outRegisterNumber);
          builder.append(" <- ");
        }
        for (int i = 0; i < instruction.inValues().size(); i++) {
          builder.append(i > 0 ? ", " : "");
          builder.append("v");
          int index = argumentMap.get(argumentMapIndex++);
          if (index >= 0) {
            builder.append(index);
          } else {
            builder.append(outRegisterNumber);
          }
        }
        if (instruction.isInvoke()) {
          builder.append("; method: ");
          builder.append(instruction.asInvokeMethod().getInvokedMethod().toSourceString());
        }
        if (instruction.isNewInstance()) {
          builder.append(instruction.asNewInstance().clazz.toSourceString());
        }
        builder.append("\n");
      }
      if (returnType == dexItemFactory.voidType) {
        builder.append("Return-Void");
      } else {
        StringUtils.appendRightPadded(builder, "Return", 20);
        builder.append("v" + (outRegisterNumber));
      }
      builder.append("\n");
      builder.append(argumentMap);
      return builder.toString();
    }
  }

  // Spot the outline opportunities in a basic block.
  // This is the superclass for both collection candidates and actually replacing code.
  // TODO(sgjesse): Collect more information in the candidate collection and reuse that for
  // replacing.
  abstract private class OutlineSpotter {

    final DexEncodedMethod method;
    final BasicBlock block;
    // instructionArrayCache is block.getInstructions() copied to an ArrayList.
    private List<Instruction> instructionArrayCache = null;

    int start;
    int index;
    int actualInstructions;
    List<Value> arguments;
    List<DexType> argumentTypes;
    List<Integer> argumentsMap;
    int argumentRegisters;
    DexType returnType;
    Value returnValue;
    int returnValueUsersLeft;
    int pendingNewInstanceIndex = -1;

    OutlineSpotter(DexEncodedMethod method, BasicBlock block) {
      this.method = method;
      this.block = block;
      reset(0);
    }

    protected List<Instruction> getInstructionArray() {
      if (instructionArrayCache == null) {
        instructionArrayCache = new ArrayList<>(block.getInstructions());
      }
      return instructionArrayCache;
    }

    // Call this before modifying block.getInstructions().
    protected void invalidateInstructionArray() {
      instructionArrayCache = null;
    }

    protected void process() {
      List<Instruction> instructions;
      for (;;) {
        instructions = getInstructionArray(); // ProcessInstruction may have invalidated it.
        if (index >= instructions.size()) {
          break;
        }
        processInstruction(instructions.get(index));
      }
    }

    // Get int in-values for an instruction. For commutative binary operations using the current
    // return value (active out-value) make sure that that value is the left value.
    protected List<Value> orderedInValues(Instruction instruction, Value returnValue) {
      List<Value> inValues = instruction.inValues();
      if (instruction.isBinop() && instruction.asBinop().isCommutative()) {
        if (inValues.get(1) == returnValue) {
          Value tmp = inValues.get(0);
          inValues.set(0, inValues.get(1));
          inValues.set(1, tmp);
        }
      }
      return inValues;
    }

    // Process instruction. Returns true if an outline candidate was found.
    private void processInstruction(Instruction instruction) {
      // Figure out whether to include the instruction.
      boolean include = false;
      int instructionIncrement = 1;
      if (instruction.isConstInstruction()) {
        if (index == start) {
          // Restart for first const instruction.
          reset(index + 1);
          return;
        } else {
          // Otherwise include const instructions.
          include = true;
          instructionIncrement = 0;
        }
      } else {
        include = canIncludeInstruction(instruction);
      }

      if (include) {
        actualInstructions += instructionIncrement;

        // Add this instruction.
        includeInstruction(instruction);
        // Check if this instruction ends the outline.
        if (actualInstructions >= options.outline.maxSize) {
          candidate(start, index + 1);
        } else {
          index++;
        }
      } else if (index > start) {
        // Do not add this instruction, candidate ends with previous instruction.
        candidate(start, index);
      } else {
        // Restart search from next instruction.
        reset(index + 1);
      }
    }

    // Check if the current instruction can be included in the outline.
    private boolean canIncludeInstruction(Instruction instruction) {
      // Find the users of the active out-value (potential return value).
      int returnValueUsersLeftIfIncluded = returnValueUsersLeft;
      if (returnValue != null) {
        for (Value value : instruction.inValues()) {
          if (value == returnValue) {
            returnValueUsersLeftIfIncluded--;
          }
        }
      }

      // If this instruction has an out-value, but the previous one is still active end the
      // outline.
      if (instruction.outValue() != null && returnValueUsersLeftIfIncluded > 0) {
        return false;
      }

      // Allow all new-instance instructions in a outline.
      if (instruction.isNewInstance()) {
        if (instruction.outValue().isUsed()) {
          // Track the new-instance value to make sure the <init> call is part of the outline.
          pendingNewInstanceIndex = index;
        }
        return true;
      }

      if (instruction.isArithmeticBinop()) {
        return true;
      }

      // Otherwise we only allow invoke.
      if (!instruction.isInvokeMethod()) {
        return false;
      }
      InvokeMethod invoke = instruction.asInvokeMethod();
      boolean constructor = dexItemFactory.isConstructor(invoke.getInvokedMethod());

      // Lookup the encoded method.
      DexMethod invokedMethod = invoke.getInvokedMethod();
      DexEncodedMethod target;
      if (invoke.isInvokeStatic()) {
        target = appInfo.lookupStaticTarget(invokedMethod);
      } else if (invoke.isInvokeDirect()) {
        target = appInfo.lookupDirectTarget(invokedMethod);
      } else {
        if (invokedMethod.getHolder().isArrayType()) {
          return false;
        }
        target = appInfo.lookupVirtualTarget(invokedMethod.getHolder(), invokedMethod);
      }
      // If the encoded method is found check the access flags.
      if (target != null) {
        if (!target.accessFlags.isPublic()) {
          return false;
        }
        DexClass holder = appInfo.definitionFor(invokedMethod.getHolder());
        if (!holder.accessFlags.isPublic()) {
          return false;
        }
      }

      // Find the number of in-going arguments, if adding this instruction.
      int newArgumentRegisters = argumentRegisters;
      if (instruction.inValues().size() > 0) {
        List<Value> inValues = orderedInValues(instruction, returnValue);
        for (int i = 0; i < inValues.size(); i++) {
          Value value = inValues.get(i);
          if (value == returnValue) {
            continue;
          }
          if (invoke.isInvokeStatic()) {
            newArgumentRegisters += value.requiredRegisters();
          } else {
            // For virtual calls only re-use the receiver argument.
            if (i > 0 || !arguments.contains(value)) {
              newArgumentRegisters += value.requiredRegisters();
            }
          }
        }
      }
      if (newArgumentRegisters > MAX_IN_SIZE) {
        return false;
      }

      // Only include constructors if the previous instruction is the corresponding new-instance.
      if (constructor) {
        if (start == index) {
          return false;
        }
        assert index > 0;
        int offset = 0;
        Instruction previous;
        List<Instruction> instructions = getInstructionArray();
        do {
          offset++;
          previous = instructions.get(index - offset);
        } while (previous.isConstInstruction());
        if (!previous.isNewInstance() || previous.outValue() != returnValue) {
          return false;
        }
        // Clear pending new instance flag as the last thing, now the matching constructor is known
        // to be included.
        pendingNewInstanceIndex = -1;
      }
      return true;
    }

    // Add the current instruction to the outline.
    private void includeInstruction(Instruction instruction) {
      List<Value> inValues = orderedInValues(instruction, returnValue);

      Value prevReturnValue = returnValue;
      if (returnValue != null) {
        for (Value value : inValues) {
          if (value == returnValue) {
            assert returnValueUsersLeft > 0;
            returnValueUsersLeft--;
          }
          if (returnValueUsersLeft == 0) {
            returnValue = null;
            returnType = dexItemFactory.voidType;
          }
        }
      }

      if (instruction.isNewInstance()) {
        assert returnValue == null;
        updateReturnValueState(instruction.outValue(), instruction.asNewInstance().clazz);
        return;
      }

      assert instruction.isInvoke()
          || instruction.isConstInstruction()
          || instruction.isArithmeticBinop();
      if (inValues.size() > 0) {
        for (int i = 0; i < inValues.size(); i++) {
          Value value = inValues.get(i);
          if (value == prevReturnValue) {
            argumentsMap.add(-1);
            continue;
          }
          if (instruction.isInvoke()
              && instruction.asInvoke().getType() != Type.STATIC
              && instruction.asInvoke().getType() != Type.CUSTOM) {
            InvokeMethod invoke = instruction.asInvokeMethod();
            int argumentIndex = arguments.indexOf(value);
            // For virtual calls only re-use the receiver argument.
            if (i == 0 && argumentIndex != -1) {
              argumentsMap.add(argumentIndex);
            } else {
              arguments.add(value);
              argumentRegisters += value.requiredRegisters();
              if (i == 0) {
                argumentTypes.add(invoke.getInvokedMethod().getHolder());
              } else {
                DexProto methodProto;
                if (instruction.asInvoke().getType() == Type.POLYMORPHIC) {
                  // Type of argument of a polymorphic call must be take from the call site.
                  methodProto = instruction.asInvokePolymorphic().getProto();
                } else {
                  methodProto = invoke.getInvokedMethod().proto;
                }
                // -1 due to receiver.
                argumentTypes.add(methodProto.parameters.values[i - 1]);
              }
              argumentsMap.add(arguments.size() - 1);
            }
          } else {
            arguments.add(value);
            if (instruction.isInvokeMethod()) {
              argumentTypes
                  .add(instruction.asInvokeMethod().getInvokedMethod().proto.parameters.values[i]);
            } else {
              argumentTypes.add(instruction.asBinop().getNumericType().dexTypeFor(dexItemFactory));
            }
            argumentsMap.add(arguments.size() - 1);
          }
        }
      }
      if (!instruction.isConstInstruction() && instruction.outValue() != null) {
        assert returnValue == null;
        if (instruction.isInvokeMethod()) {
          updateReturnValueState(
              instruction.outValue(),
              instruction.asInvokeMethod().getInvokedMethod().proto.returnType);
        } else {
          updateReturnValueState(
              instruction.outValue(),
              instruction.asBinop().getNumericType().dexTypeFor(dexItemFactory));
        }
      }
    }

    private void updateReturnValueState(Value newReturnValue, DexType newReturnType) {
      returnValueUsersLeft = newReturnValue.numberOfAllUsers();
      // If the return value is not used don't track it.
      if (returnValueUsersLeft == 0) {
        returnValue = null;
        returnType = dexItemFactory.voidType;
      } else {
        returnValue = newReturnValue;
        returnType = newReturnType;
      }
    }


    protected abstract void handle(int start, int end, Outline outline);

    private void candidate(int start, int index) {
      List<Instruction> instructions = getInstructionArray();
      assert !instructions.get(start).isConstInstruction();

      if (pendingNewInstanceIndex != -1) {
        if (pendingNewInstanceIndex == start) {
          reset(index);
        } else {
          reset(pendingNewInstanceIndex);
        }
        return;
      }

      // Back out of any const instructions ending this candidate.
      int end = index;
      while (instructions.get(end - 1).isConstInstruction()) {
        end--;
      }

      // Check if the candidate qualifies.
      int nonConstInstructions = 0;
      for (int i = start; i < end; i++) {
        if (!instructions.get(i).isConstInstruction()) {
          nonConstInstructions++;
        }
      }
      if (nonConstInstructions < options.outline.minSize) {
        reset(start + 1);
        return;
      }

      Outline outline = new Outline(
          instructions, arguments, argumentTypes, argumentsMap, returnType, start, end);
      handle(start, end, outline);

      // Start a new candidate search from the next instruction after this outline.
      reset(index);
    }

    // Restart the collection of outline candidate to the given instruction start index.
    private void reset(int startIndex) {
      start = startIndex;
      index = startIndex;
      actualInstructions = 0;
      arguments = new ArrayList<>(MAX_IN_SIZE);
      argumentTypes = new ArrayList<>(MAX_IN_SIZE);
      argumentsMap = new ArrayList<>(MAX_IN_SIZE);
      argumentRegisters = 0;
      returnType = dexItemFactory.voidType;
      returnValue = null;
      returnValueUsersLeft = 0;
      pendingNewInstanceIndex = -1;
    }
  }

  // Collect outlining candidates with the methods that can use them.
  // TODO(sgjesse): This does not take several usages in the same method into account.
  private class OutlineIdentifier extends OutlineSpotter {

    OutlineIdentifier(DexEncodedMethod method, BasicBlock block) {
      super(method, block);
    }

    @Override
    protected void handle(int start, int end, Outline outline) {
      synchronized (candidates) {
        candidates.computeIfAbsent(outline, k -> new ArrayList<>()).add(method);
      }
    }
  }

  // Replace instructions with a call to the outlined method.
  private class OutlineRewriter extends OutlineSpotter {

    private final IRCode code;
    private final ListIterator<BasicBlock> blocksIterator;
    private final List<Integer> toRemove;
    int argumentsMapIndex;

    OutlineRewriter(
        DexEncodedMethod method, IRCode code,
        ListIterator<BasicBlock> blocksIterator, BasicBlock block, List<Integer> toRemove) {
      super(method, block);
      this.code = code;
      this.blocksIterator = blocksIterator;
      this.toRemove = toRemove;
    }

    @Override
    protected void handle(int start, int end, Outline outline) {
      if (candidates.containsKey(outline)) {
        DexMethod m = generatedOutlines.get(outline);
        assert m != null;
        List<Value> in = new ArrayList<>();
        Value returnValue = null;
        argumentsMapIndex = 0;
        Position position = Position.none();
        { // Scope for 'instructions'.
          List<Instruction> instructions = getInstructionArray();
          for (int i = start; i < end; i++) {
            Instruction current = instructions.get(i);
            if (current.isConstInstruction()) {
              // Leave any const instructions.
              continue;
            }
            if (position.isNone()) {
              position = current.getPosition();
            }
            // Prepare to remove the instruction.
            List<Value> inValues = orderedInValues(current, returnValue);
            for (int j = 0; j < inValues.size(); j++) {
              Value value = inValues.get(j);
              value.removeUser(current);
              int argumentIndex = outline.argumentMap.get(argumentsMapIndex++);
              if (argumentIndex >= in.size()) {
                assert argumentIndex == in.size();
                in.add(value);
              }
            }
            if (current.outValue() != null) {
              returnValue = current.outValue();
            }
            // The invoke of the outline method will be placed at the last instruction index,
            // so don't mark that for removal.
            if (i < end - 1) {
              toRemove.add(i);
            }
          }
        }
        assert m.proto.shorty.toString().length() - 1 == in.size();
        if (returnValue != null && !returnValue.isUsed()) {
          returnValue = null;
        }
        Invoke outlineInvoke = new InvokeStatic(m, returnValue, in);
        outlineInvoke.setBlock(block);
        outlineInvoke.setPosition(position);
        if (position.isNone() && code.doAllThrowingInstructionsHavePositions()) {
          // We have introduced a static invoke, but non of the outlines instructions could throw
          // and none had a position. The code no longer has the previous property.
          code.setAllThrowingInstructionsHavePositions(false);
        }
        InstructionListIterator endIterator = block.listIterator(end - 1);
        Instruction instructionBeforeEnd = endIterator.next();
        invalidateInstructionArray(); // Because we're about to modify the original linked list.
        instructionBeforeEnd.clearBlock();
        endIterator.set(outlineInvoke); // Replaces instructionBeforeEnd.
        if (block.hasCatchHandlers()) {
          // If the inserted invoke is inserted in a block with handlers, split the block after
          // the inserted invoke.
          endIterator.split(code, blocksIterator);
        }
      }
    }
  }

  public Outliner(AppInfo appInfo, InternalOptions options) {
    this.appInfo = appInfo;
    this.dexItemFactory = appInfo.dexItemFactory;
    this.options = options;
  }

  public void identifyCandidates(IRCode code, DexEncodedMethod method) {
    assert !(method.getCode() instanceof OutlineCode);
    for (BasicBlock block : code.blocks) {
      new OutlineIdentifier(method, block).process();
    }
  }

  public boolean selectMethodsForOutlining() {
    assert methodsSelectedForOutlining.size() == 0;
    List<Outline> toRemove = new ArrayList<>();
    for (Entry<Outline, List<DexEncodedMethod>> entry : candidates.entrySet()) {
      if (entry.getValue().size() < options.outline.threshold) {
        toRemove.add(entry.getKey());
      } else {
        methodsSelectedForOutlining.addAll(entry.getValue());
      }
    }
    for (Outline outline : toRemove) {
      candidates.remove(outline);
    }
    return methodsSelectedForOutlining.size() > 0;
  }

  public Set<DexEncodedMethod> getMethodsSelectedForOutlining() {
    return methodsSelectedForOutlining;
  }

  public void applyOutliningCandidate(IRCode code, DexEncodedMethod method) {
    assert !(method.getCode() instanceof OutlineCode);
    ListIterator<BasicBlock> blocksIterator = code.blocks.listIterator();
    while (blocksIterator.hasNext()) {
      BasicBlock block = blocksIterator.next();
      List<Integer> toRemove = new ArrayList<>();
      new OutlineRewriter(method, code, blocksIterator, block, toRemove).process();
      block.removeInstructions(toRemove);
    }
  }

  static public void noProcessing(IRCode code, DexEncodedMethod method) {
    // No operation.
  }

  private class OutlineSourceCode implements SourceCode {

    final private Outline outline;
    int argumentMapIndex = 0;

    OutlineSourceCode(Outline outline) {
      this.outline = outline;
    }

    @Override
    public int instructionCount() {
      return outline.templateInstructions.size() + 1;
    }

    @Override
    public int instructionIndex(int instructionOffset) {
      return instructionOffset;
    }

    @Override
    public int instructionOffset(int instructionIndex) {
      return instructionIndex;
    }

    @Override
    public DebugLocalInfo getCurrentLocal(int register) {
      return null;
    }

    @Override
    public int traceInstruction(int instructionIndex, IRBuilder builder) {
      // There is just one block, and after the last instruction it is closed.
      return instructionIndex == outline.templateInstructions.size() ? instructionIndex : -1;
    }

    @Override
    public void closingCurrentBlockWithFallthrough(
        int fallthroughInstructionIndex, IRBuilder builder) {
    }

    @Override
    public void setUp() {
    }

    @Override
    public void clear() {
    }

    @Override
    public void buildPrelude(IRBuilder builder) {
      // Fill in the Argument instructions in the argument block.
      for (int i = 0; i < outline.arguments.size(); i++) {
        ValueType valueType = outline.arguments.get(i).outType();
        builder.addNonThisArgument(i, valueType);
      }
    }

    @Override
    public void buildPostlude(IRBuilder builder) {
      // Intentionally left empty. (Needed for Java-bytecode-frontend synchronization support.)
    }

    @Override
    public void buildInstruction(IRBuilder builder, int instructionIndex) {
      if (instructionIndex == outline.templateInstructions.size()) {
        if (outline.returnType == dexItemFactory.voidType) {
          builder.addReturn();
        } else {
          builder.addReturn(ValueType.fromDexType(outline.returnType), outline.argumentCount());
        }
        return;
      }
      // Build IR from the template.
      Instruction template = outline.templateInstructions.get(instructionIndex);
      List<Value> inValues = new ArrayList<>(template.inValues().size());
      List<Value> templateInValues = template.inValues();
      // The template in-values are not re-ordered for commutative binary operations, as it does
      // not matter.
      for (int i = 0; i < templateInValues.size(); i++) {
        Value value = templateInValues.get(i);
        int register = outline.argumentMap.get(argumentMapIndex++);
        if (register == -1) {
          register = outline.argumentCount();
        }
        inValues.add(
            builder.readRegister(register, value.outType()));
      }
      Value outValue = null;
      if (template.outValue() != null) {
        Value value = template.outValue();
        outValue = builder
            .writeRegister(outline.argumentCount(), value.outType(), ThrowingInfo.CAN_THROW);
      }

      Instruction newInstruction = null;
      if (template.isInvoke()) {
        Invoke templateInvoke = template.asInvoke();
        newInstruction = Invoke.createFromTemplate(templateInvoke, outValue, inValues);
      } else if (template.isAdd()) {
        Add templateInvoke = template.asAdd();
        newInstruction = new Add(
            templateInvoke.getNumericType(), outValue, inValues.get(0), inValues.get(1));
      } else if (template.isMul()) {
        Mul templateInvoke = template.asMul();
        newInstruction = new Mul(
            templateInvoke.getNumericType(), outValue, inValues.get(0), inValues.get(1));
      } else if (template.isSub()) {
        Sub templateInvoke = template.asSub();
        newInstruction = new Sub(
            templateInvoke.getNumericType(), outValue, inValues.get(0), inValues.get(1));
      } else if (template.isDiv()) {
        Div templateInvoke = template.asDiv();
        newInstruction = new Div(
            templateInvoke.getNumericType(), outValue, inValues.get(0), inValues.get(1));
      } else if (template.isRem()) {
        Rem templateInvoke = template.asRem();
        newInstruction = new Rem(
            templateInvoke.getNumericType(), outValue, inValues.get(0), inValues.get(1));
      } else {
        assert template.isNewInstance();
        NewInstance templateNewInstance = template.asNewInstance();
        newInstruction = new NewInstance(templateNewInstance.clazz, outValue);
      }
      builder.add(newInstruction);
    }

    @Override
    public void resolveAndBuildSwitch(int value, int fallthroughOffset, int payloadOffset,
        IRBuilder builder) {
      throw new Unreachable("Unexpected call to resolveAndBuildSwitch");
    }

    @Override
    public void resolveAndBuildNewArrayFilledData(int arrayRef, int payloadOffset,
        IRBuilder builder) {
      throw new Unreachable("Unexpected call to resolveAndBuildNewArrayFilledData");
    }

    @Override
    public CatchHandlers<Integer> getCurrentCatchHandlers() {
      return null;
    }

    @Override
    public int getMoveExceptionRegister() {
      throw new Unreachable();
    }

    @Override
    public Position getDebugPositionAtOffset(int offset) {
      throw new Unreachable();
    }

    @Override
    public Position getCurrentPosition() {
      return Position.none();
    }

    @Override
    public boolean verifyCurrentInstructionCanThrow() {
      // TODO(sgjesse): Check more here?
      return true;
    }

    @Override
    public boolean verifyLocalInScope(DebugLocalInfo local) {
      return true;
    }

    @Override
    public boolean verifyRegister(int register) {
      return true;
    }
  }

  public class OutlineCode extends Code {

    private Outline outline;

    OutlineCode(Outline outline) {
      this.outline = outline;
    }

    @Override
    public boolean isOutlineCode() {
      return true;
    }

    @Override
    public int estimatedSizeForInlining() {
      // We just onlined this, so do not inline it again.
      return Integer.MAX_VALUE;
    }

    @Override
    public OutlineCode asOutlineCode() {
      return this;
    }

    @Override
    public IRCode buildIR(DexEncodedMethod encodedMethod, InternalOptions options)
        throws ApiLevelException {
      OutlineSourceCode source = new OutlineSourceCode(outline);
      IRBuilder builder = new IRBuilder(encodedMethod, source, options);
      return builder.build();
    }

    @Override
    public String toString() {
      return outline.toString();
    }

    @Override
    public void registerReachableDefinitions(UseRegistry registry) {
      throw new Unreachable();
    }

    @Override
    protected int computeHashCode() {
      return outline.hashCode();
    }

    @Override
    protected boolean computeEquals(Object other) {
      return outline.equals(other);
    }

    @Override
    public String toString(DexEncodedMethod method, ClassNameMapper naming) {
      return null;
    }
  }


  public DexProgramClass buildOutlinerClass(DexType type) {
    if (candidates.size() == 0) {
      return null;
    }

    // Build the outlined methods.
    DexEncodedMethod[] direct = new DexEncodedMethod[candidates.size()];
    int count = 0;

    // By now the candidates are the actual selected outlines. Name the generated methods in a
    // consistent order, to provide deterministic output.
    List<Outline> outlines = new ArrayList<>(candidates.keySet());
    outlines.sort(Comparator.naturalOrder());
    for (Outline outline : outlines) {
      MethodAccessFlags methodAccess =
          MethodAccessFlags.fromSharedAccessFlags(
              Constants.ACC_PUBLIC | Constants.ACC_STATIC, false);
      DexString methodName = dexItemFactory.createString(options.outline.methodPrefix + count);
      DexMethod method = outline.buildMethod(type, methodName);
      direct[count] = new DexEncodedMethod(method, methodAccess, DexAnnotationSet.empty(),
          DexAnnotationSetRefList.empty(), new OutlineCode(outline));
      generatedOutlines.put(outline, method);
      count++;
    }
    // No need to sort the direct methods as they are generated in sorted order.

    // Build the outliner class.
    DexType superType = dexItemFactory.createType("Ljava/lang/Object;");
    DexTypeList interfaces = DexTypeList.empty();
    DexString sourceFile = dexItemFactory.createString("outline");
    ClassAccessFlags accessFlags = ClassAccessFlags.fromSharedAccessFlags(Constants.ACC_PUBLIC);
    DexProgramClass clazz = new DexProgramClass(
        type,
        null,
        null,
        accessFlags,
        superType,
        interfaces,
        sourceFile,
        // TODO: Build dex annotations structure.
        DexAnnotationSet.empty(),
        DexEncodedField.EMPTY_ARRAY, // Static fields.
        DexEncodedField.EMPTY_ARRAY, // Instance fields.
        direct,
        DexEncodedMethod.EMPTY_ARRAY // Virtual methods.
    );

    return clazz;
  }
}
