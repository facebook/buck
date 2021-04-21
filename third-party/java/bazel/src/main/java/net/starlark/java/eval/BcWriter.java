package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import net.starlark.java.syntax.FileLocations;
import net.starlark.java.syntax.Resolver;

/** Utility to build bytecode. */
class BcWriter {

  /** Marker address for yet unknown forward jump. */
  private static final int FORWARD_JUMP_ADDR = -17;

  private final FileLocations fileLocations;
  private final Module module;
  /** Function name. */
  private final String name;
  /** Number of local variables. */
  private final int nlocals;

  private final ImmutableList<Resolver.Binding> locals;
  private final ImmutableList<Resolver.Binding> freeVars;

  /** {@code 0..ip} of the array is bytecode. */
  private int[] text = ArraysForStarlark.EMPTY_INT_ARRAY;
  /** Current instruction pointer. */
  private int ip = 0;

  /** Number of currently allocated registers. */
  private int slots;
  /** Total number of registers needed to execute this function. */
  private int maxSlots;

  /** The stack of for statements. */
  private ArrayList<CurrentFor> fors = new ArrayList<>();
  /** Max depth of for loops. */
  private int maxLoopDepth = 0;

  /** Alternating instr, file locations offset */
  private final BcInstrToLoc.Builder instrToLoc;

  /** Starlark values as constant registers. */
  private IndexedList<Object> constSlots = new IndexedList<>();
  /** Strings referenced in currently built bytecode. */
  private IndexedList<String> strings = new IndexedList<>();
  /** Other untyped objects referenced in currently built bytecode. */
  private ArrayList<Object> objects = new ArrayList<>();

  BcWriter(FileLocations fileLocations, Module module, String name,
      ImmutableList<Resolver.Binding> locals,
      ImmutableList<Resolver.Binding> freeVars) {
    this.fileLocations = fileLocations;
    this.module = module;
    this.name = name;
    this.nlocals = locals.size();

    this.slots = nlocals;
    this.locals = locals;
    this.freeVars = freeVars;
    this.maxSlots = slots;
    this.instrToLoc = new BcInstrToLoc.Builder(fileLocations);
  }

  int getSlots() {
    return slots;
  }

  /** Allocate a register. */
  int allocSlot() {
    int r = slots++;
    maxSlots = Math.max(slots, maxSlots);
    return r;
  }

  /**
   * Deallocate all registers (except constant registers); done after each statement; since
   * registered are not shared between statements, only local variables are.
   */
  void decallocateAllSlots() {
    slots = nlocals;
  }

  /**
   * Store a string in a string pool, return an index of that string. Note these strings are
   * special strings like variable or field names. These are not constant registers.
   */
  int allocString(String s) {
    return strings.index(s);
  }

  /**
   * Store an arbitrary object in an object storage; the object store is not a const registers.
   */
  int allocObject(Object o) {
    Preconditions.checkNotNull(o);
    int r = objects.size();
    objects.add(o);
    return r;
  }

  /** Allocate constant slot. */
  int allowConstSlot(Object value) {
    return this.constSlots.index(value) | BcSlot.CONST_FLAG;
  }

  void writeThrowException(LocOffset locOffset, String message) {
    write(BcInstr.Opcode.EVAL_EXCEPTION, locOffset, allocString(message));
  }

  void writeContinue(LocOffset locOffset) {
    if (fors.isEmpty()) {
      writeThrowException(locOffset, "continue statement must be inside a for loop");
    } else {
      write(
          BcInstr.Opcode.CONTINUE,
          locOffset,
          currentFor().nextValueSlot,
          currentFor().bodyIp,
          BcWriter.FORWARD_JUMP_ADDR);
      currentFor().endsToPatch.add(ip - 1);
    }
  }

  void writeBreak(LocOffset locOffset) {
    if (fors.isEmpty()) {
      writeThrowException(locOffset, "break statement must be inside a for loop");
    } else {
      write(BcInstr.Opcode.BREAK, locOffset, BcWriter.FORWARD_JUMP_ADDR);
      currentFor().endsToPatch.add(ip - 1);
    }
  }

  void writeForInit(LocOffset collectionLocOffset, int collectionSlot, int nextValueSlot) {
    write(BcInstr.Opcode.FOR_INIT, collectionLocOffset, collectionSlot, nextValueSlot, FORWARD_JUMP_ADDR);

    int endToPatch = ip - 1;

    CurrentFor currentFor = new CurrentFor(ip, nextValueSlot);
    fors.add(currentFor);
    currentFor.endsToPatch.add(endToPatch);

    maxLoopDepth = Math.max(fors.size(), maxLoopDepth);
  }

  void writeForClose() {
    for (int endsToPatch : currentFor().endsToPatch) {
      patchForwardJump(endsToPatch);
    }
    fors.remove(fors.size() - 1);
  }

  /** Saved writer state. */
  class SavedState {
    private final int savedSlotCount = slots;
    private final int savedIp = ip;
    private final int constCount = constSlots.values.size();
    private final int stringCount = strings.values.size();
    private final int objectCount = objects.size();

    private SavedState() {
    }

    /** Restore previous compiler state. */
    void reset() {
      Preconditions.checkState(slots >= savedSlotCount);
      Preconditions.checkState(ip >= savedIp);

      slots = savedSlotCount;
      instrToLoc.reset(savedIp);
      ip = savedIp;
      constSlots.reset(constCount);
      strings.reset(stringCount);
      while (objects.size() != objectCount) {
        objects.remove(objects.size() - 1);
      }
    }
  }

  SavedState save() {
    return new SavedState();
  }

  /** Store values indexed by an integer. */
  private static class IndexedList<T> {
    private ArrayList<T> values = new ArrayList<>();
    private HashMap<Object, Integer> index = new HashMap<>();

    /** Be able to store 1 and 1.0 in index as distinct entries, while the objects are equal. */
    private static class StarlarkFloatWrapper {
      private final StarlarkFloat f;

      public StarlarkFloatWrapper(StarlarkFloat f) {
        this.f = f;
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (o == null || getClass() != o.getClass()) {
          return false;
        }
        StarlarkFloatWrapper that = (StarlarkFloatWrapper) o;
        return f.equals(that.f);
      }

      @Override
      public int hashCode() {
        return Objects.hash(f);
      }
    }

    private Object makeKey(T value) {
      if (value instanceof StarlarkFloat) {
        return new StarlarkFloatWrapper((StarlarkFloat) value);
      } else {
        return value;
      }
    }

    int index(T s) {
      Preconditions.checkNotNull(s);
      return index.computeIfAbsent(
          makeKey(s),
          k -> {
            int r = values.size();
            values.add(s);
            return r;
          });
    }

    public void reset(int constCount) {
      while (values.size() != constCount) {
        Object last = makeKey(values.get(values.size() - 1));
        values.remove(values.size() - 1);
        Preconditions.checkState(index.remove(last) != null);
      }
    }

    public T[] toArray(T[] emptyArray) {
      return values.toArray(emptyArray);
    }
  }

  /** Newtype for offset within {@link #fileLocations}. */
  static class LocOffset {
    private final int offset;

    LocOffset(int offset) {
      this.offset = offset;
    }
  }

  /** Write complete opcode with validation. */
  void write(BcInstr.Opcode opcode, LocOffset locOffset, int... args) {
    instrToLoc.add(ip, locOffset.offset);

    int prevIp = ip;

    int instrLen = BcInstr.INSTR_HEADER_LEN + args.length;
    if (ip + instrLen > text.length) {
      text = Arrays.copyOf(text, Math.max(text.length * 2, ip + instrLen));
    }

    text[ip++] = opcode.ordinal();
    System.arraycopy(args, 0, text, ip, args.length);
    ip += args.length;

    if (Bc.ASSERTIONS) {
      int expectedArgCount =
          opcode.operands.codeSize(
              text, prevIp + BcInstr.INSTR_HEADER_LEN);
      Preconditions.checkState(
          expectedArgCount == args.length,
          "incorrect signature for %s: expected %s, actual %s",
          opcode,
          expectedArgCount,
          args.length);
    }
  }

  /**
   * Write forward condition jump instruction. Return an address to be patched when the jump
   * address is known.
   */
  int writeForwardCondJump(BcInstr.Opcode opcode, LocOffset locOffset, int cond) {
    Preconditions.checkState(
        opcode == BcInstr.Opcode.IF_BR || opcode == BcInstr.Opcode.IF_NOT_BR);
    write(opcode, locOffset, cond, FORWARD_JUMP_ADDR);
    return ip - 1;
  }

  /**
   * Write unconditional forward jump. Return an address to be patched when the jump address is
   * known.
   */
  int writeForwardJump(LocOffset locOffset) {
    write(BcInstr.Opcode.BR, locOffset, FORWARD_JUMP_ADDR);
    return ip - 1;
  }

  /** Patch previously registered forward jump address. */
  void patchForwardJump(int ip) {
    Preconditions.checkState(text[ip] == FORWARD_JUMP_ADDR);
    text[ip] = this.ip;
  }

  /** Current for block in the compiler; used to compile break and continue statements. */
  private static class CurrentFor {
    /** Instruction pointer of the for statement body. */
    private final int bodyIp;

    /**
     * Register which stores next iterator value. This register is updated by {@code FOR_INIT} and
     * {@code CONTINUE} instructions.
     */
    private final int nextValueSlot;

    /**
     * Pointers to the pointers to the end of the for statement body; patched in the end of the for
     * compilation.
     */
    private ArrayList<Integer> endsToPatch = new ArrayList<>();

    private CurrentFor(int bodyIp, int nextValueSlot) {
      this.bodyIp = bodyIp;
      this.nextValueSlot = nextValueSlot;
    }
  }

  /** Closest containing for statement. */
  private CurrentFor currentFor() {
    return fors.get(fors.size() - 1);
  }

  BcCompiled finish() {
    return new BcCompiled(
        name,
        fileLocations,
        locals,
        freeVars,
        module,
        strings.toArray(ArraysForStarlark.EMPTY_STRING_ARRAY),
        objects.toArray(ArraysForStarlark.EMPTY_OBJECT_ARRAY),
        Arrays.copyOf(text, ip),
        maxSlots,
        constSlots.toArray(ArraysForStarlark.EMPTY_OBJECT_ARRAY),
        maxLoopDepth,
        instrToLoc.build());
  }

}
