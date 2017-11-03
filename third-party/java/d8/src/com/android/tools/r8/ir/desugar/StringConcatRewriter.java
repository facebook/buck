// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.DexCallSite;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexValue;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.ConstString;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.InvokeCustom;
import com.android.tools.r8.ir.code.InvokeDirect;
import com.android.tools.r8.ir.code.InvokeVirtual;
import com.android.tools.r8.ir.code.NewInstance;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.code.ValueType;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

/** String concatenation desugaring rewriter. */
public class StringConcatRewriter {
  private static final String CONCAT_FACTORY_TYPE_DESCR = "Ljava/lang/invoke/StringConcatFactory;";
  private static final String CALLSITE_TYPE_DESCR = "Ljava/lang/invoke/CallSite;";
  private static final String LOOKUP_TYPE_DESCR = "Ljava/lang/invoke/MethodHandles$Lookup;";
  private static final String METHOD_TYPE_TYPE_DESCR = "Ljava/lang/invoke/MethodType;";

  private static final String MAKE_CONCAT = "makeConcat";
  private static final String MAKE_CONCAT_WITH_CONSTANTS = "makeConcatWithConstants";
  private static final String TO_STRING = "toString";
  private static final String APPEND = "append";

  private final DexItemFactory factory;

  private final DexMethod makeConcat;
  private final DexMethod makeConcatWithConstants;

  private final DexMethod stringBuilderInit;
  private final DexMethod stringBuilderToString;

  private final Map<DexType, DexMethod> paramTypeToAppendMethod = new IdentityHashMap<>();
  private final DexMethod defaultAppendMethod;

  public StringConcatRewriter(DexItemFactory factory) {
    assert factory != null;
    this.factory = factory;

    DexType factoryType = factory.createType(CONCAT_FACTORY_TYPE_DESCR);
    DexType callSiteType = factory.createType(CALLSITE_TYPE_DESCR);
    DexType lookupType = factory.createType(LOOKUP_TYPE_DESCR);
    DexType methodTypeType = factory.createType(METHOD_TYPE_TYPE_DESCR);

    makeConcat = factory.createMethod(factoryType,
        factory.createProto(callSiteType, lookupType, factory.stringType, methodTypeType),
        factory.createString(MAKE_CONCAT));

    makeConcatWithConstants = factory.createMethod(factoryType,
        factory.createProto(callSiteType, lookupType, factory.stringType, methodTypeType,
            factory.stringType, factory.objectArrayType),
        factory.createString(MAKE_CONCAT_WITH_CONSTANTS));

    stringBuilderInit = factory.createMethod(
        factory.stringBuilderType, factory.createProto(factory.voidType),
        factory.createString(Constants.INSTANCE_INITIALIZER_NAME));

    stringBuilderToString = factory.createMethod(
        factory.stringBuilderType, factory.createProto(factory.stringType),
        factory.createString(TO_STRING));

    // Mapping of type parameters to methods of StringBuilder.
    DexType stringBuilderType = factory.stringBuilderType;
    paramTypeToAppendMethod.put(factory.booleanType, factory.createMethod(
        stringBuilderType, factory.createProto(stringBuilderType, factory.booleanType), APPEND));
    paramTypeToAppendMethod.put(factory.charType, factory.createMethod(
        stringBuilderType, factory.createProto(stringBuilderType, factory.charType), APPEND));
    paramTypeToAppendMethod.put(factory.byteType, factory.createMethod(
        stringBuilderType, factory.createProto(stringBuilderType, factory.intType), APPEND));
    paramTypeToAppendMethod.put(factory.shortType, factory.createMethod(
        stringBuilderType, factory.createProto(stringBuilderType, factory.intType), APPEND));
    paramTypeToAppendMethod.put(factory.intType, factory.createMethod(
        stringBuilderType, factory.createProto(stringBuilderType, factory.intType), APPEND));
    paramTypeToAppendMethod.put(factory.longType, factory.createMethod(
        stringBuilderType, factory.createProto(stringBuilderType, factory.longType), APPEND));
    paramTypeToAppendMethod.put(factory.floatType, factory.createMethod(
        stringBuilderType, factory.createProto(stringBuilderType, factory.floatType), APPEND));
    paramTypeToAppendMethod.put(factory.doubleType, factory.createMethod(
        stringBuilderType, factory.createProto(stringBuilderType, factory.doubleType), APPEND));
    paramTypeToAppendMethod.put(factory.stringType, factory.createMethod(
        stringBuilderType, factory.createProto(stringBuilderType, factory.stringType), APPEND));
    defaultAppendMethod = factory.createMethod(
        stringBuilderType, factory.createProto(stringBuilderType, factory.objectType), APPEND);
  }

  /**
   * Find and desugar all string concatenations implemented via `invokedynamic` call
   * to either of StringConcatFactory bootstrap methods.
   */
  public void desugarStringConcats(DexMethod method, IRCode code) {
    ListIterator<BasicBlock> blocks = code.listIterator();
    while (blocks.hasNext()) {
      BasicBlock block = blocks.next();
      InstructionListIterator instructions = block.listIterator();
      while (instructions.hasNext()) {
        Instruction instruction = instructions.next();
        if (!instruction.isInvokeCustom()) {
          continue;
        }

        InvokeCustom invokeCustom = instruction.asInvokeCustom();
        DexCallSite callSite = invokeCustom.getCallSite();

        // We are interested in bootstrap methods StringConcatFactory::makeConcat
        // and StringConcatFactory::makeConcatWithConstants, both are static.
        if (!callSite.bootstrapMethod.type.isInvokeStatic()) {
          continue;
        }

        DexMethod bootstrapMethod = callSite.bootstrapMethod.asMethod();
        // We rely on both rewrite methods called below performing their work in
        // a way which keeps both `instructions` and `blocks` iterators in
        // valid state so that we can continue iteration.
        if (bootstrapMethod == this.makeConcat) {
          rewriteMakeConcat(method, code, blocks, instructions, invokeCustom);
        } else if (bootstrapMethod == this.makeConcatWithConstants) {
          rewriteMakeConcatWithConstants(method, code, blocks, instructions, invokeCustom);
        }
      }
    }
  }

  /**
   * Rewrite concatenation with StringConcatFactory::makeConcat​(...). There is no
   * format string (`recipe`), all arguments are just concatenated in order.
   */
  private void rewriteMakeConcat(DexMethod method, IRCode code, ListIterator<BasicBlock> blocks,
      InstructionListIterator instructions, InvokeCustom invokeCustom) {
    DexProto proto = invokeCustom.getCallSite().methodProto;
    DexType[] parameters = proto.parameters.values;
    int paramCount = parameters.length;
    List<Value> arguments = invokeCustom.inValues();

    // Signature of the callsite proto defines the effective types of the arguments.
    if (paramCount != arguments.size()) {
      throw error(method, "inconsistent arguments: expected " +
          paramCount + ", actual " + arguments.size());
    }

    // Collect chunks.
    ConcatBuilder builder = new ConcatBuilder(code, blocks, instructions);
    for (int i = 0; i < paramCount; i++) {
      builder.addChunk(arguments.get(i),
          paramTypeToAppendMethod.getOrDefault(parameters[i], defaultAppendMethod));
    }

    // Desugar the instruction.
    builder.desugar();
  }

  /**
   * Rewrite concatenation with StringConcatFactory::makeConcat​WithConstants(...).
   * There is a format string (`recipe`) specifying where exactly the arguments are
   * to be inserted into a template string `recipe`.
   *
   * NOTE: `makeConcat​WithConstants` also supports passing compilation time `constants`
   * as bootstrap method arguments, but current version seems to only support String
   * constants. This method does not support desugaring of `makeConcatWithConstants`
   * with non-string constants provided as bootstrap method arguments.
   */
  private void rewriteMakeConcatWithConstants(
      DexMethod method, IRCode code, ListIterator<BasicBlock> blocks,
      InstructionListIterator instructions, InvokeCustom invokeCustom) {
    DexCallSite callSite = invokeCustom.getCallSite();
    DexProto proto = callSite.methodProto;
    DexType[] parameters = proto.parameters.values;
    int paramCount = parameters.length;
    List<Value> callArgs = invokeCustom.inValues();
    List<DexValue> bootstrapArgs = callSite.bootstrapArgs;

    // Signature of the callsite proto defines the effective types of the arguments.
    if (paramCount != callArgs.size()) {
      throw error(method, "inconsistent arguments: expected " +
          paramCount + ", actual " + callArgs.size());
    }

    // Get `recipe` string.
    if (bootstrapArgs.size() == 0) {
      throw error(method, "bootstrap method misses `recipe` argument");
    }

    // Constant arguments to `recipe`.
    List<DexValue> constArgs = new ArrayList<>();
    for (int i = 1; i < bootstrapArgs.size(); i++) {
      constArgs.add(bootstrapArgs.get(i));
    }

    // Extract recipe.
    DexValue recipeValue = bootstrapArgs.get(0);
    if (!(recipeValue instanceof DexValue.DexValueString)) {
      throw error(method, "bootstrap method argument `recipe` must be a string");
    }
    String recipe = ((DexValue.DexValueString) recipeValue).getValue().toString();

    // Collect chunks and patch the instruction.
    ConcatBuilder builder = new ConcatBuilder(code, blocks, instructions);
    StringBuilder acc = new StringBuilder();
    int argIndex = 0;
    int constArgIndex = 0;
    int length = recipe.length();
    for (int i = 0; i < length; i++) {
      char c = recipe.charAt(i);
      if (c == '\u0001') {
        // Reference to an argument.
        if (acc.length() > 0) {
          builder.addChunk(acc.toString(), paramTypeToAppendMethod.get(factory.stringType));
          acc.setLength(0);
        }
        if (argIndex >= paramCount) {
          throw error(method, "too many argument references in `recipe`");
        }
        builder.addChunk(callArgs.get(argIndex),
            paramTypeToAppendMethod.getOrDefault(parameters[argIndex], defaultAppendMethod));
        argIndex++;

      } else if (c == '\u0002') {
        if (constArgIndex >= constArgs.size()) {
          throw error(method, "too many constant references in `recipe`");
        }

        // Reference to a constant. Since it's a constant we just convert it to
        // string and append to `acc`, this way we will avoid calling toString()
        // on every call.
        acc.append(convertToString(method, constArgs.get(constArgIndex++)));

      } else {
        acc.append(c);
      }
    }

    if (argIndex != paramCount) {
      throw error(method, "too few argument references in `recipe`, "
          + "expected " + paramCount + ", referenced: " + argIndex);
    }
    if (constArgIndex != constArgs.size()) {
      throw error(method, "too few constant references in `recipe`, "
          + "expected " + constArgs.size() + ", referenced: " + constArgIndex);
    }

    // Final part.
    if (acc.length() > 0) {
      builder.addChunk(acc.toString(), paramTypeToAppendMethod.get(factory.stringType));
    }

    // Desugar the instruction.
    builder.desugar();
  }

  private static String convertToString(DexMethod method, DexValue value) {
    if (value instanceof DexValue.DexValueString) {
      return ((DexValue.DexValueString) value).getValue().toString();
    }
    throw error(method,
        "const arg referenced from `recipe` is not supported: " + value.getClass().getName());
  }

  private final class ConcatBuilder {
    private final IRCode code;
    private final ListIterator<BasicBlock> blocks;
    private final InstructionListIterator instructions;
    private final Instruction invokeCustom;
    private final BasicBlock currentBlock;
    private final List<Chunk> chunks = new ArrayList<>();

    private ConcatBuilder(
        IRCode code, ListIterator<BasicBlock> blocks, InstructionListIterator instructions) {
      this.code = code;
      this.blocks = blocks;
      this.instructions = instructions;

      invokeCustom = instructions.peekPrevious();
      assert invokeCustom.isInvokeCustom();
      currentBlock = invokeCustom.getBlock();
    }

    private void appendInstruction(Instruction instruction) {
      instruction.setPosition(invokeCustom.getPosition());
      instructions.add(instruction);
    }

    final void addChunk(Value value, DexMethod method) {
      chunks.add(new ArgumentChunk(value, method));
    }

    final void addChunk(String str, DexMethod method) {
      chunks.add(new ConstantChunk(str, method));
    }

    /**
     * Patch current `invoke-custom` instruction with:
     * <pre>
     *   prologue:
     *      |   new-instance v0, StringBuilder
     *      |   invoke-direct {v0}, void StringBuilder.<init>()
     *
     *   populate each chunk:
     *      |   (optional) load the constant, e.g.: const-string v1, ""
     *      |   invoke-virtual {v0, v1}, StringBuilder StringBuilder.append([type])
     *
     *   epilogue:
     *      |   invoke-virtual {v0}, String StringBuilder.toString()
     *
     * </pre>
     */
    final void desugar() {
      // Move the iterator before the invoke-custom we are about to patch.
      instructions.previous();

      // new-instance v0, StringBuilder
      Value sbInstance = code.createValue(ValueType.OBJECT);
      appendInstruction(new NewInstance(factory.stringBuilderType, sbInstance));

      // invoke-direct {v0}, void StringBuilder.<init>()
      appendInstruction(new InvokeDirect(stringBuilderInit,
          null /* no return value */, Collections.singletonList(sbInstance)));

      // Add calls to append(...) methods
      for (Chunk chunk : chunks) {
        chunk.addAppendCall(sbInstance);
      }

      // invoke-virtual {v0}, String StringBuilder.toString()
      Instruction nextInstruction = instructions.next();
      assert invokeCustom == nextInstruction;

      // The value representing the string: we reuse the value from the
      // original invoke-custom instruction, and thus all its usages.
      Value concatValue = invokeCustom.outValue();
      if (concatValue == null) {
        // The out value might be empty in case it was optimized out.
        concatValue = code.createValue(ValueType.OBJECT);
      }

      // Replace the instruction.
      instructions.replaceCurrentInstruction(new InvokeVirtual(
          stringBuilderToString, concatValue, Collections.singletonList(sbInstance)));

      if (!currentBlock.hasCatchHandlers()) {
        return;
      }

      // Since the block has handlers we should split the block at exception throwing
      // instructions. Splitting blocks while adding instructions seems more complicated.
      //
      // NOTE: we collect new blocks first and copy catch handlers from the original
      // one after all blocks are split. Copying handlers just after splitting involves
      // extra complexity since split() method expects that the block being split is
      // located right before the iterator point and new blocks created while copying
      // handles break this expectation.
      List<BasicBlock> newBlocks = new ArrayList<>();
      InstructionListIterator it = currentBlock.listIterator();
      while (it.hasNext()) {
        Instruction instruction = it.next();
        if (instruction.instructionTypeCanThrow() && it.hasNext()) {
          // We split block in case we see throwing instruction which
          // is not the last instruction of the block.
          BasicBlock newBlock = it.split(code, blocks);
          newBlocks.add(newBlock);
          // Follow with the next block.
          it = newBlock.listIterator();
        }
      }
      // Copy catch handlers after all blocks are split.
      for (BasicBlock newBlock : newBlocks) {
        newBlock.copyCatchHandlers(code, blocks, currentBlock);
      }
    }

    private abstract class Chunk {
      final DexMethod method;

      Chunk(DexMethod method) {
        this.method = method;
      }

      abstract Value getOrCreateValue();

      final void addAppendCall(Value sbInstance) {
        appendInstruction(new InvokeVirtual(
            method, null /* don't care about return value */,
            Lists.newArrayList(sbInstance, getOrCreateValue())));
      }
    }

    private final class ArgumentChunk extends Chunk {
      final Value value;

      ArgumentChunk(Value value, DexMethod method) {
        super(method);
        this.value = value;
      }

      @Override
      Value getOrCreateValue() {
        return value;
      }
    }

    private final class ConstantChunk extends Chunk {
      final String str;

      ConstantChunk(String str, DexMethod method) {
        super(method);
        this.str = str;
      }

      @Override
      Value getOrCreateValue() {
        Value value = code.createValue(ValueType.OBJECT);
        appendInstruction(new ConstString(value, factory.createString(str)));
        return value;
      }
    }
  }

  private static CompilationError error(DexMethod method, String message) {
    return new CompilationError(
        "String concatenation desugaring error (method: " +
            method.qualifiedName() + "): " + message);
  }
}
