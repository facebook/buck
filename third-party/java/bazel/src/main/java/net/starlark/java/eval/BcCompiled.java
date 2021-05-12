package net.starlark.java.eval;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.syntax.FileLocations;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.Resolver;

/**
 * Function body as a bytecode block.
 */
class BcCompiled {

  /**
   * For debugging.
   */
  private final String name;
  /**
   * For errors.
   */
  private final FileLocations fileLocations;

  private final ImmutableList<Resolver.Binding> locals;
  private final ImmutableList<Resolver.Binding> freeVars;
  private final Module module;
  /**
   * Strings references by the bytecode.
   */
  final String[] strings;
  /**
   * Other objects references by the bytecode.
   */
  final Object[] objects;
  /**
   * The bytecode.
   */
  final int[] text;
  /**
   * Number of registers.
   */
  final int slotCount;
  /**
   * Registers holding constants.
   */
  final Object[] constSlots;
  /**
   * Max depths of for loops.
   */
  final int loopDepth;
  /**
   * Instruction pointer to a location offset.
   *
   * <p>Key is a beginning of an instruction.
   */
  final BcInstrToLoc instrToLoc;
  @Nullable
  private final Object returnsConst;
  @Nullable
  private final String returnsTypeIs;

  BcCompiled(
      String name,
      FileLocations fileLocations,
      ImmutableList<Resolver.Binding> locals,
      ImmutableList<Resolver.Binding> freeVars,
      Module module,
      String[] strings,
      Object[] objects,
      int[] text,
      int slotCount,
      Object[] constSlots,
      int loopDepth,
      BcInstrToLoc instrToLoc,
      @Nullable
      Object returnsConst,
      @Nullable
      String returnsTypeIs) {
    this.name = name;
    this.fileLocations = fileLocations;
    this.locals = locals;
    this.freeVars = freeVars;
    this.module = module;
    this.strings = strings;
    this.objects = objects;
    this.text = text;
    this.slotCount = slotCount;
    this.constSlots = constSlots;
    this.loopDepth = loopDepth;
    this.instrToLoc = instrToLoc;
    this.returnsConst = returnsConst;
    this.returnsTypeIs = returnsTypeIs;
  }

  public ImmutableList<Resolver.Binding> getLocals() {
    return locals;
  }

  public ImmutableList<Resolver.Binding> getFreeVars() {
    return freeVars;
  }

  public FileLocations getFileLocations() {
    return fileLocations;
  }

  @Override
  public String toString() {
    return toStringImpl(name, text, new BcInstrOperand.OpcodePrinterFunctionContext(
            getLocalNames(),
            module.getResolverModule().getGlobalNamesSlow(),
            getFreeVarNames()),
        Arrays.asList(strings), Arrays.asList(constSlots), Arrays.asList(objects));
  }

  private ImmutableList<String> getFreeVarNames() {
    return freeVars.stream().map(Resolver.Binding::getName)
        .collect(ImmutableList.toImmutableList());
  }

  private ImmutableList<String> getLocalNames() {
    return locals.stream().map(Resolver.Binding::getName).collect(ImmutableList.toImmutableList());
  }

  ImmutableList<String> toStringInstructions() {
    return toStringInstructionsImpl(text, new BcInstrOperand.OpcodePrinterFunctionContext(
            getLocalNames(),
            module.getResolverModule().getGlobalNamesSlow(),
            getFreeVarNames()),
        Arrays.asList(strings), Arrays.asList(constSlots), Arrays.asList(objects));
  }

  @VisibleForTesting
  static String toStringImpl(String name, int[] text,
      BcInstrOperand.OpcodePrinterFunctionContext fnCtx,
      List<String> strings, List<Object> constants, List<Object> objects) {
    return "def " + name + "; " + String
        .join("; ", toStringInstructionsImpl(text, fnCtx, strings, constants, objects));
  }

  private static ImmutableList<String> toStringInstructionsImpl(int[] text,
      BcInstrOperand.OpcodePrinterFunctionContext fnCtx,
      List<String> strings, List<Object> constants, List<Object> objects) {
    ImmutableList.Builder<String> ret = ImmutableList.builder();
    BcParser parser = new BcParser(text);
    while (!parser.eof()) {
      StringBuilder sb = new StringBuilder();
      sb.append(parser.getIp()).append(": ");
      BcInstr.Opcode opcode = parser.nextOpcode();
      sb.append(opcode);
      String argsString =
          opcode.operands.toStringAndCount(
              parser, strings, constants, objects, fnCtx);

      sb.append(" ").append(argsString);
      ret.add(sb.toString());
    }
    // It's useful to know the final address in case someone wants to jump to that address
    ret.add(parser.getIp() + ": EOF");
    return ret.build();
  }

  /**
   * Instruction opcode at IP.
   */
  BcInstr.Opcode instrOpcodeAt(int ip) {
    return BcInstr.Opcode.values()[text[ip]];
  }

  /**
   * Instruction length at IP.
   */
  public int instrLenAt(int ip) {
    return BcInstr.INSTR_HEADER_LEN
        + instrOpcodeAt(ip)
        .operands
        .codeSize(text, ip + BcInstr.INSTR_HEADER_LEN);
  }

  @VisibleForTesting
  ImmutableList<BcInstr.Decoded> instructions() {
    ImmutableList.Builder<BcInstr.Decoded> instructions = ImmutableList.builder();
    BcParser parser = new BcParser(text);
    while (!parser.eof()) {
      BcInstr.Opcode opcode = parser.nextOpcode();
      instructions.add(new BcInstr.Decoded(opcode, opcode.operands.decode(parser)));
    }
    return instructions.build();
  }

  public Location locationAt(int ip) {
    return instrToLoc.locationAt(ip);
  }

  @Nullable
  public Object returnConst() {
    return returnsConst;
  }

  /** Check if this function body is equivalent to {@code type(x) == 'yyy'}. */
  @Nullable
  public String returnTypeIs() {
    return returnsTypeIs;
  }
}
