package net.starlark.java.eval;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Argument;
import net.starlark.java.syntax.AssignmentStatement;
import net.starlark.java.syntax.BinaryOperatorExpression;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.Comprehension;
import net.starlark.java.syntax.ConditionalExpression;
import net.starlark.java.syntax.DefStatement;
import net.starlark.java.syntax.DictExpression;
import net.starlark.java.syntax.DotExpression;
import net.starlark.java.syntax.Expression;
import net.starlark.java.syntax.ExpressionStatement;
import net.starlark.java.syntax.FloatLiteral;
import net.starlark.java.syntax.FlowStatement;
import net.starlark.java.syntax.ForStatement;
import net.starlark.java.syntax.Identifier;
import net.starlark.java.syntax.IfStatement;
import net.starlark.java.syntax.IndexExpression;
import net.starlark.java.syntax.IntLiteral;
import net.starlark.java.syntax.LambdaExpression;
import net.starlark.java.syntax.ListExpression;
import net.starlark.java.syntax.LoadStatement;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.Node;
import net.starlark.java.syntax.Parameter;
import net.starlark.java.syntax.Resolver;
import net.starlark.java.syntax.ReturnStatement;
import net.starlark.java.syntax.SliceExpression;
import net.starlark.java.syntax.Statement;
import net.starlark.java.syntax.StringLiteral;
import net.starlark.java.syntax.TokenKind;
import net.starlark.java.syntax.UnaryOperatorExpression;

/** Starlark bytecode compiler. */
class Bc {

  /**
   * This constant enables/disables assertions in Starlark interpreter: when turned on, it checks
   * that:
   *
   * <ul>
   *   <li>Compiler generates valid opcode arguments, according to opcode spec
   *   <li>Interpreter decodes opcode arguments correctly (e. g. does not consume extra undeclared
   *       argument)
   * </ul>
   *
   * Turn assertions on when debugging the compiler or interpreter.
   *
   * <p>Note the assertions are turned on when tests are launched from Bazel.
   */
  public static final boolean ASSERTIONS = Boolean.getBoolean("starlark.bc.assertions")
      || System.getenv("STARLARK_BC_ASSERTIONS") != null;

  static {
    if (ASSERTIONS) {
      System.err.println();
      System.err.println();
      System.err.println("Java Starlark internal runtime assertions enabled.");
      System.err.println();
      System.err.println();
    }
  }

  /** Function body as a bytecode block. */
  public static class Compiled {

    /** Original function. This is used only for debugging here. */
    private final Resolver.Function rfn;
    private final Module module;
    /** Strings references by the bytecode. */
    public final String[] strings;
    /** Other objects references by the bytecode. */
    public final Object[] objects;
    /** The bytecode. */
    public final int[] text;
    /** Number of registers. */
    public final int slotCount;
    /** Registers holding constants. */
    public final Object[] constSlots;
    /** Max depths of for loops. */
    public final int loopDepth;
    /**
     * Instruction pointer to a node.
     *
     * <p>Key is a beginning of an instruction.
     */
    public final ImmutableMap<Integer, Node> instrToNode;

    private Compiled(
        Resolver.Function rfn,
        Module module,
        String[] strings,
        Object[] objects,
        int[] text,
        int slotCount,
        Object[] constSlots,
        int loopDepth,
        ImmutableMap<Integer, Node> instrToNode) {
      this.rfn = rfn;
      this.module = module;
      this.strings = strings;
      this.objects = objects;
      this.text = text;
      this.slotCount = slotCount;
      this.constSlots = constSlots;
      this.loopDepth = loopDepth;
      this.instrToNode = instrToNode;
    }

    @Override
    public String toString() {
      return toStringImpl(text, new BcInstrOperand.OpcodeVisitorFunctionContext(rfn, module),
          Arrays.asList(strings), Arrays.asList(constSlots));
    }

    ImmutableList<String> toStringInstructions() {
      return toStringInstructionsImpl(text, new BcInstrOperand.OpcodeVisitorFunctionContext(rfn, module),
          Arrays.asList(strings), Arrays.asList(constSlots));
    }

    @VisibleForTesting
    static String toStringImpl(int[] text, BcInstrOperand.OpcodeVisitorFunctionContext fnCtx,
        List<String> strings, List<Object> constants) {
      return String.join("; ", toStringInstructionsImpl(text, fnCtx, strings, constants));
    }

    private static ImmutableList<String> toStringInstructionsImpl(int[] text, BcInstrOperand.OpcodeVisitorFunctionContext fnCtx,
        List<String> strings, List<Object> constants) {
      ImmutableList.Builder<String> ret = ImmutableList.builder();
      int ip = 0;
      while (ip != text.length) {
        StringBuilder sb = new StringBuilder();
        sb.append(ip).append(": ");
        int opcode1 = text[ip++];
        BcInstr.Opcode opcode = BcInstr.Opcode.values()[opcode1];
        sb.append(opcode);
        int[] updateIp = new int[] {ip};
        String argsString =
            opcode.operands.toStringAndCount(
                updateIp, text, strings, constants, fnCtx);
        ip = updateIp[0];
        sb.append(" ").append(argsString);
        ret.add(sb.toString());
      }
      // It's useful to know the final address in case someone wants to jump to that address
      ret.add(ip + ": EOF");
      return ret.build();
    }

    /** Instruction opcode at IP. */
    BcInstr.Opcode instrOpcodeAt(int ip) {
      return BcInstr.Opcode.values()[text[ip]];
    }

    /** Instruction length at IP. */
    public int instrLenAt(int ip) {
      return BcInstr.INSTR_HEADER_LEN
          + instrOpcodeAt(ip)
              .operands
              .codeSize(
                  rfn,
                  module,
                  text,
                  Arrays.asList(strings),
                  Arrays.asList(constSlots),
                  ip + BcInstr.INSTR_HEADER_LEN);
    }

    public ImmutableList<BcInstr.Decoded> instructions() {
      ImmutableList.Builder<BcInstr.Decoded> instructions = ImmutableList.builder();
      int ip = 0;
      while (ip != text.length) {
        BcInstr.Opcode opcode = instrOpcodeAt(ip);
        int len = instrLenAt(ip);
        instructions.add(new BcInstr.Decoded(
            opcode, Arrays.copyOfRange(text, ip + BcInstr.INSTR_HEADER_LEN, ip + len)));
        ip += len;
      }
      return instructions.build();
    }

    public Location locationAt(int ip) {
      Node node = nodeAt(ip);
      Location loc;
      if (node instanceof BinaryOperatorExpression) {
        loc = ((BinaryOperatorExpression) node).getOperatorLocation();
      } else if (node instanceof IndexExpression) {
        loc = ((IndexExpression) node).getLbracketLocation();
      } else if (node instanceof SliceExpression) {
        loc = ((SliceExpression) node).getLbracketLocation();
      } else if (node instanceof DotExpression) {
        loc = ((DotExpression) node).getDotLocation();
      } else if (node instanceof AssignmentStatement) {
        loc = ((AssignmentStatement) node).getOperatorLocation();
      } else {
        loc = node.getStartLocation();
      }
      return loc;
    }

    public Node nodeAt(int ip) {
      Node node = instrToNode.get(ip);
      Preconditions.checkState(node != null, "Node is not defined at " + ip);
      return node;
    }
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

    @SuppressWarnings("unchecked")
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

  /**
   * The compiler implementation.
   */
  private static class Compiler {
    private final int nlocals;
    @javax.annotation.Nonnull
    private final Resolver.Function rfn;
    private final Module module;
    private final int[] globalIndex;
    private final Tuple freevars;
    /** {@code 0..ip} of the array is bytecode. */
    private int[] text = ArraysForStarlark.EMPTY_INT_ARRAY;
    /** Current instruction pointer. */
    private int ip = 0;
    /** Number of currently allocated registers. */
    private int slots;
    /** Total number of registers needed to execute this function. */
    private int maxSlots;

    /** Starlark values as constant registers. */
    private IndexedList<Object> constSlots = new IndexedList<>();

    /** Strings referenced in currently built bytecode. */
    private IndexedList<String> strings = new IndexedList<>();

    /** Other untyped objects referenced in currently built bytecode. */
    private ArrayList<Object> objects = new ArrayList<>();

    /** The stack of for statements. */
    private ArrayList<CurrentFor> fors = new ArrayList<>();
    /** Max depth of for loops. */
    private int maxLoopDepth = 0;

    /** Alternating instr, node */
    private ArrayList<Object> instrToNode = new ArrayList<>();

    private Compiler(Resolver.Function rfn, Module module, int[] globalIndex,
        Tuple freevars) {
      this.rfn = rfn;
      this.nlocals = rfn.getLocals().size();
      this.module = module;
      this.globalIndex = globalIndex;
      this.freevars = freevars;
      this.slots = this.nlocals;
      this.maxSlots = slots;
    }

    private class SavedState {
      int savedSlotCount = slots;
      int savedIp = ip;
      int constCount = constSlots.values.size();
      int stringCount = strings.values.size();
      int objectCount = objects.size();

      /** Restore previous compiler state. */
      void reset() {
        Preconditions.checkState(slots >= savedSlotCount);
        Preconditions.checkState(ip >= savedIp);

        slots = savedSlotCount;
        while (!instrToNode.isEmpty() && ((int) instrToNode.get(instrToNode.size() - 2) >= savedIp)) {
          instrToNode.remove(instrToNode.size() - 1);
          instrToNode.remove(instrToNode.size() - 1);
        }
        ip = savedIp;
        constSlots.reset(constCount);
        strings.reset(stringCount);
        while (objects.size() != objectCount) {
          objects.remove(objects.size() - 1);
        }
      }
    }

    private SavedState save() {
      return new SavedState();
    }

    /** Closest containing for statement. */
    private CurrentFor currentFor() {
      return fors.get(fors.size() - 1);
    }

    /** Allocate a register. */
    private int allocSlot() {
      int r = slots++;
      maxSlots = Math.max(slots, maxSlots);
      return r;
    }

    /**
     * Deallocate all registers (except constant registers); done after each statement; since
     * registered are not shared between statements, only local variables are.
     */
    private void decallocateAllSlots() {
      slots = nlocals;
    }

    /**
     * Store a string in a string pool, return an index of that string. Note these strings are
     * special strings like variable or field names. These are not constant registers.
     */
    private int allocString(String s) {
      return strings.index(s);
    }

    /**
     * Store an arbitrary object in an object storage; the object store is not a const registers.
     */
    private int allocObject(Object o) {
      Preconditions.checkNotNull(o);
      int r = objects.size();
      objects.add(o);
      return r;
    }

    /** Write complete opcode with validation. */
    private void write(BcInstr.Opcode opcode, Node node, int... args) {
      instrToNode.add(ip);
      instrToNode.add(node);

      int prevIp = ip;

      int instrLen = BcInstr.INSTR_HEADER_LEN + args.length;
      if (ip + instrLen > text.length) {
        text = Arrays.copyOf(text, Math.max(text.length * 2, ip + instrLen));
      }

      text[ip++] = opcode.ordinal();
      System.arraycopy(args, 0, text, ip, args.length);
      ip += args.length;

      if (ASSERTIONS) {
        int expectedArgCount =
            opcode.operands.codeSize(
                rfn,
                module,
                text, strings.values, constSlots.values, prevIp + BcInstr.INSTR_HEADER_LEN);
        Preconditions.checkState(
            expectedArgCount == args.length,
            "incorrect signature for %s: expected %s, actual %s",
            opcode,
            expectedArgCount,
            args.length);
      }
    }

    private void cp(Node node, int from, int to) {

      // Sanity check preconditions

      Preconditions.checkArgument(BcSlot.isValidSourceSlot(from));
      if ((from & BcSlot.MASK) == BcSlot.LOCAL_FLAG) {
        Preconditions.checkArgument((from & ~BcSlot.MASK) < slots);
      }

      Preconditions.checkArgument((to & BcSlot.MASK) == BcSlot.LOCAL_FLAG);
      Preconditions.checkArgument((to & ~BcSlot.MASK) < slots);

      // This optimizes away assignment `x = x`
      if (from == to) {
        return;
      }

      write(BcInstr.Opcode.CP, node, from, to);
    }

    /** Marker address for yet unknown forward jump. */
    private static final int FORWARD_JUMP_ADDR = -17;

    /**
     * Write forward condition jump instruction. Return an address to be patched when the jump
     * address is known.
     */
    private int writeForwardCondJump(BcInstr.Opcode opcode, Node expression, int cond) {
      Preconditions.checkState(
          opcode == BcInstr.Opcode.IF_BR || opcode == BcInstr.Opcode.IF_NOT_BR);
      write(opcode, expression, cond, FORWARD_JUMP_ADDR);
      return ip - 1;
    }

    /**
     * Write unconditional forward jump. Return an address to be patched when the jump address is
     * known.
     */
    private int writeForwardJump(Node expression) {
      write(BcInstr.Opcode.BR, expression, FORWARD_JUMP_ADDR);
      return ip - 1;
    }

    /** Patch previously registered forward jump address. */
    private void patchForwardJump(int ip) {
      Preconditions.checkState(text[ip] == FORWARD_JUMP_ADDR);
      text[ip] = this.ip;
    }

    /** Compile. */
    private void compileStatements(List<Statement> statements, boolean postAssignHook) {
      for (Statement statement : statements) {
        compileStatement(statement, postAssignHook);
      }
    }

    private void compileStatement(Statement statement, boolean postAssignHook) {
      // No registers are shared across statements.
      // We could implement precise register tracking, but there is no need for that at the moment.
      decallocateAllSlots();

      if (statement instanceof ExpressionStatement) {
        // Likely doc comment, skip it
        if (((ExpressionStatement) statement).getExpression() instanceof StringLiteral) {
          return;
        }

        // Do not assign it anywhere
        compileExpression(((ExpressionStatement) statement).getExpression());
      } else if (statement instanceof AssignmentStatement) {
        compileAssignment((AssignmentStatement) statement, postAssignHook);
      } else if (statement instanceof ReturnStatement) {
        ReturnStatement returnStatement = (ReturnStatement) statement;
        if (returnStatement.getResult() == null) {
          write(BcInstr.Opcode.RETURN, returnStatement, BcSlot.NULL_FLAG);
        } else {
          int result = compileExpression(returnStatement.getResult()).slot;
          write(BcInstr.Opcode.RETURN, returnStatement, result);
        }
      } else if (statement instanceof IfStatement) {
        compileIfStatement((IfStatement) statement);
      } else if (statement instanceof ForStatement) {
        compileForStatement((ForStatement) statement);
      } else if (statement instanceof FlowStatement) {
        compileFlowStatement((FlowStatement) statement);
      } else if (statement instanceof LoadStatement) {
        compileLoadStatement((LoadStatement) statement);
      } else if (statement instanceof DefStatement) {
        compileDefStatement((DefStatement) statement);
      } else {
        throw new RuntimeException("not impl: " + statement.getClass().getSimpleName());
      }
    }

    private void compileLoadStatement(LoadStatement loadStatement) {
      write(BcInstr.Opcode.LOAD_STMT, loadStatement, allocObject(loadStatement));
    }

    private void compileDefStatement(DefStatement def) {
      int result = allocSlot();
      compileNewFunction(def.getResolvedFunction(), def, result);
      compileSet(result, def.getIdentifier(), true);
    }

    /** Common code to compile def and lambda. */
    private void compileNewFunction(Resolver.Function rfn, Node node, int result) {
      // Evaluate default value expressions of optional parameters.
      // We use MANDATORY to indicate a required parameter
      // (not null, because defaults must be a legal tuple value, as
      // it will be constructed by the code emitted by the compiler).
      // As an optimization, we omit the prefix of MANDATORY parameters.

      int nparams =
          rfn.getParameters().size() - (rfn.hasKwargs() ? 1 : 0) - (rfn.hasVarargs() ? 1 : 0);

      int ndefaults = 0;
      ImmutableList<Parameter> parameters = rfn.getParameters();
      for (int p = 0; p < parameters.size(); p++) {
        Parameter parameter = parameters.get(p);
        if (parameter.getDefaultValue() != null) {
          ndefaults = nparams - p;
          break;
        }
      }

      int[] args = new int[3 + ndefaults];
      int i = 0;
      args[i++] = allocObject(rfn);
      args[i++] = ndefaults;

      for (int p = 0; p < ndefaults; ++p) {
        Parameter parameter = parameters.get(nparams - ndefaults + p);
        if (parameter.getDefaultValue() != null) {
          args[i++] = compileExpression(parameter.getDefaultValue()).slot;
        } else {
          args[i++] = compileConstant(StarlarkFunction.MANDATORY).slot;
        }
      }

      args[i++] = result;
      Preconditions.checkState(i == args.length);

      write(BcInstr.Opcode.NEW_FUNCTION, node, args);
    }

    private void compileIfStatement(IfStatement ifStatement) {
      Expression condExpr = ifStatement.getCondition();

      int cond;
      BcInstr.Opcode elseBrOpcode;
      if (condExpr instanceof UnaryOperatorExpression
          && ((UnaryOperatorExpression) condExpr).getOperator() == TokenKind.NOT) {
        // special case `if not cond: ...` micro-optimization
        cond = compileExpression(((UnaryOperatorExpression) condExpr).getX()).slot;
        elseBrOpcode = BcInstr.Opcode.IF_BR;
      } else {
        cond = compileExpression(condExpr).slot;
        elseBrOpcode = BcInstr.Opcode.IF_NOT_BR;
      }

      int elseBlock = writeForwardCondJump(elseBrOpcode, ifStatement, cond);
      compileStatements(ifStatement.getThenBlock(), false);
      if (ifStatement.getElseBlock() != null) {
        int end = writeForwardJump(ifStatement);
        patchForwardJump(elseBlock);
        compileStatements(ifStatement.getElseBlock(), false);
        patchForwardJump(end);
      } else {
        patchForwardJump(elseBlock);
      }
    }

    private void compileFlowStatement(FlowStatement flowStatement) {
      switch (flowStatement.getKind()) {
        case BREAK:
          compileBreak(flowStatement);
          break;
        case CONTINUE:
          compileContinue(flowStatement);
          break;
        case PASS:
          // nop
          break;
        default:
          throw new IllegalStateException("unknown flow statement: " + flowStatement.getKind());
      }
    }

    private void compileContinue(Node node) {
      if (fors.isEmpty()) {
        compileThrowException(node, "continue statement must be inside a for loop");
      } else {
        write(
            BcInstr.Opcode.CONTINUE,
            node,
            currentFor().nextValueSlot,
            currentFor().bodyIp,
            FORWARD_JUMP_ADDR);
        currentFor().endsToPatch.add(ip - 1);
      }
    }

    private void compileBreak(Node node) {
      if (fors.isEmpty()) {
        compileThrowException(node, "break statement must be inside a for loop");
      } else {
        write(BcInstr.Opcode.BREAK, node, FORWARD_JUMP_ADDR);
        currentFor().endsToPatch.add(ip - 1);
      }
    }

    /** Callback invoked to compile the loop body. */
    private interface ForBody {
      void compile();
    }

    /** Generic compile for loop routine, used in for statement and in loop comprehension. */
    private void compileFor(Expression vars, Expression collection, ForBody body) {
      CompileExpressionResult iterable = compileExpression(collection);

      // Register where we are storing the next iterator value.
      // This register is update by FOR_INIT and CONTINUE instructions.
      int nextValueSlot = allocSlot();

      write(BcInstr.Opcode.FOR_INIT, collection, iterable.slot, nextValueSlot, FORWARD_JUMP_ADDR);
      int endToPatch = ip - 1;

      CurrentFor currentFor = new CurrentFor(ip, nextValueSlot);
      fors.add(currentFor);
      currentFor.endsToPatch.add(endToPatch);

      compileAssignment(currentFor.nextValueSlot, vars, false);

      maxLoopDepth = Math.max(fors.size(), maxLoopDepth);

      body.compile();

      // We use usual CONTINUE statement in the end of the loop.
      // Note: CONTINUE does unnecessary goto e in the end of iteration.
      compileContinue(collection);

      for (int endsToPatch : currentFor.endsToPatch) {
        patchForwardJump(endsToPatch);
      }
      fors.remove(fors.size() - 1);
    }

    private void compileForStatement(ForStatement forStatement) {
      compileFor(
          forStatement.getVars(),
          forStatement.getCollection(),
          () -> compileStatements(forStatement.getBody(), false));
    }

    private void compileAssignment(
        AssignmentStatement assignmentStatement, boolean postAssignHook) {
      if (assignmentStatement.isAugmented()) {
        compileAgumentedAssignment(assignmentStatement);
      } else {
        compileAssignmentRegular(assignmentStatement, postAssignHook);
      }
    }

    private void compileAssignmentRegular(
        AssignmentStatement assignmentStatement, boolean postAssignHook) {
      Preconditions.checkState(!assignmentStatement.isAugmented());

      Expression lhs = assignmentStatement.getLHS();
      if (lhs instanceof Identifier) {
        Identifier lhsIdent = (Identifier) lhs;
        if (lhsIdent.getBinding().getScope() == Resolver.Scope.LOCAL && !postAssignHook) {
          compileExpressionTo(assignmentStatement.getRHS(), lhsIdent.getBinding().getIndex() | BcSlot.LOCAL_FLAG);
          return;
        }
      }

      int rhs = compileExpression(assignmentStatement.getRHS()).slot;
      compileAssignment(rhs, lhs, postAssignHook);
    }

    private void compileAssignment(int rhs, Expression lhs, boolean postAssignHook) {
      if (lhs instanceof Identifier) {
        compileSet(rhs, (Identifier) lhs, postAssignHook);
      } else if (lhs instanceof ListExpression) {
        compileAssignmentToList(rhs, (ListExpression) lhs, postAssignHook);
      } else if (lhs instanceof IndexExpression) {
        IndexExpression indexExpression = (IndexExpression) lhs;
        int object = compileExpression(indexExpression.getObject()).slot;
        int key = compileExpression(indexExpression.getKey()).slot;
        write(BcInstr.Opcode.SET_INDEX, lhs, object, key, rhs);
      } else {
        compileThrowException(lhs, String.format("cannot assign to '%s'", lhs));
      }
    }

    private void compileAssignmentToList(int rhs, ListExpression list, boolean postAssignHook) {
      int[] componentRegs =
          IntStream.range(0, list.getElements().size()).map(i1 -> allocSlot()).toArray();

      int[] args = new int[2 + list.getElements().size()];
      args[0] = rhs;
      args[1] = list.getElements().size();
      System.arraycopy(componentRegs, 0, args, 2, componentRegs.length);
      write(BcInstr.Opcode.UNPACK, list, args);

      for (int i = 0; i < componentRegs.length; i++) {
        int componentReg = componentRegs[i];
        compileAssignment(componentReg, list.getElements().get(i), postAssignHook);
      }
    }

    private void compileSet(int rhs, Identifier identifier, boolean postAssignHook) {
      Resolver.Binding binding = identifier.getBinding();
      switch (binding.getScope()) {
        case LOCAL:
          cp(identifier, rhs, binding.getIndex());
          return;
        case GLOBAL:
          int globalVarIndex = this.globalIndex[binding.getIndex()];
          write(
              BcInstr.Opcode.SET_GLOBAL,
              identifier,
              rhs,
              globalVarIndex,
              allocString(identifier.getName()),
              postAssignHook ? 1 : 0);
          return;
        case CELL:
          write(
              BcInstr.Opcode.SET_CELL,
              identifier,
              rhs,
              binding.getIndex());
          return;
        default:
          throw new IllegalStateException();
      }

    }

    private void compileThrowException(Node node, String message) {
      // All incorrect AST should be resolved by the resolver,
      // compile code to throw exception as a stopgap.
      write(BcInstr.Opcode.EVAL_EXCEPTION, node, allocString(message));
    }

    private void compileAgumentedAssignment(AssignmentStatement assignmentStatement) {
      Preconditions.checkState(assignmentStatement.getOperator() != null);
      if (assignmentStatement.getLHS() instanceof Identifier) {
        int rhs = compileExpression(assignmentStatement.getRHS()).slot;
        Identifier lhs = (Identifier) assignmentStatement.getLHS();
        CompileExpressionResult value = compileGet(lhs);
        int temp = allocSlot();
        cp(lhs, value.slot, temp);
        write(
            BcInstr.Opcode.BINARY_IN_PLACE,
            assignmentStatement,
            temp,
            rhs,
            assignmentStatement.getOperator().ordinal(),
            temp);
        compileSet(temp, lhs, false);
      } else if (assignmentStatement.getLHS() instanceof IndexExpression) {
        IndexExpression indexExpression = (IndexExpression) assignmentStatement.getLHS();

        int object = compileExpression(indexExpression.getObject()).slot;
        int key = compileExpression(indexExpression.getKey()).slot;
        int rhs = compileExpression(assignmentStatement.getRHS()).slot;
        int temp = allocSlot();
        write(BcInstr.Opcode.INDEX, assignmentStatement, object, key, temp);
        write(
            BcInstr.Opcode.BINARY_IN_PLACE,
            assignmentStatement,
            temp,
            rhs,
            assignmentStatement.getOperator().ordinal(),
            temp);
        write(BcInstr.Opcode.SET_INDEX, assignmentStatement, object, key, temp);
      } else if (assignmentStatement.getLHS() instanceof ListExpression) {
        compileThrowException(
            assignmentStatement.getLHS(),
            "cannot perform augmented assignment on a list or tuple expression");
      } else {
        compileThrowException(
            assignmentStatement.getLHS(),
            String.format("cannot assign to '%s'", assignmentStatement.getLHS()));
      }
    }

    /** Compile a constant, return a register containing the constant. */
    private CompileExpressionResult compileConstant(Object constant) {
      int slot = constSlots.index(constant) | BcSlot.CONST_FLAG;
      return new CompileExpressionResult(slot, constant);
    }

    private CompileExpressionResult compileConstantTo(Node node, Object constant, int result) {
      CompileExpressionResult constResult = compileConstant(constant);
      if (result == BcSlot.ANY_FLAG) {
        return constResult;
      } else {
        cp(node, constResult.slot, result);
        return new CompileExpressionResult(result, constant);
      }
    }

    /** Compile an expression, store result in provided register. */
    private CompileExpressionResult compileExpressionTo(Expression expression, int result) {
      if (expression instanceof SliceExpression) {
        return compileSliceExpression((SliceExpression) expression, result);
      } else if (expression instanceof Comprehension) {
        return compileComprehension((Comprehension) expression, result);
      } else if (expression instanceof ListExpression) {
        return compileList((ListExpression) expression, result);
      } else if (expression instanceof DictExpression) {
        return compileDict((DictExpression) expression, result);
      } else if (expression instanceof CallExpression) {
        return compileCall((CallExpression) expression, result);
      } else if (expression instanceof ConditionalExpression) {
        return compileConditional((ConditionalExpression) expression, result);
      } else if (expression instanceof DotExpression) {
        return compileDot((DotExpression) expression, result);
      } else if (expression instanceof IndexExpression) {
        return compileIndex((IndexExpression) expression, result);
      } else if (expression instanceof UnaryOperatorExpression) {
        return compileUnaryOperator((UnaryOperatorExpression) expression, result);
      } else if (expression instanceof BinaryOperatorExpression) {
        return compileBinaryOperator((BinaryOperatorExpression) expression, result);
      } else if (expression instanceof LambdaExpression) {
        return compileLambda((LambdaExpression) expression, result);
      } else if (expression instanceof Identifier
          || expression instanceof StringLiteral
          || expression instanceof IntLiteral
          || expression instanceof FloatLiteral) {
        CompileExpressionResult exprResult = compileExpression(expression);
        if (result != BcSlot.ANY_FLAG) {
          cp(expression, exprResult.slot, result);
        } else {
          result = exprResult.slot;
        }
        return new CompileExpressionResult(result, exprResult.value);
      } else {
        throw new RuntimeException("not impl: " + expression.getClass().getSimpleName());
      }
    }

    private CompileExpressionResult compileLambda(LambdaExpression lambda, int result) {
      if (result == BcSlot.ANY_FLAG) {
        result = allocSlot();
      }
      compileNewFunction(lambda.getResolvedFunction(), lambda, result);
      return new CompileExpressionResult(result, null);
    }

    private CompileExpressionResult compileIntLiteral(IntLiteral intLiteral) {
      StarlarkInt starlarkInt = intLiteralValueAsStarlarInt(intLiteral);
      return compileConstant(starlarkInt);
    }

    private StarlarkInt intLiteralValueAsStarlarInt(IntLiteral intLiteral) {
      Number value = intLiteral.getValue();
      StarlarkInt starlarkInt;
      if (value instanceof Integer) {
        starlarkInt = StarlarkInt.of((Integer) value);
      } else if (value instanceof Long) {
        starlarkInt = StarlarkInt.of((Long) value);
      } else if (value instanceof BigInteger) {
        starlarkInt = StarlarkInt.of((BigInteger) value);
      } else {
        throw new IllegalStateException();
      }
      return starlarkInt;
    }

    private static class CompileExpressionResult {
      private final int slot;
      @Nullable
      private final Object value;

      private CompileExpressionResult(int slot, @Nullable Object value) {
        Preconditions.checkArgument(BcSlot.isValidSourceSlot(slot));
        this.slot = slot;
        this.value = value;
        if (Bc.ASSERTIONS) {
          if (value != null) {
            Starlark.checkValid(value);
          }
        }
      }

      @Override
      public String toString() {
        return "CompileExpressionResult{"
            + "slot=" + BcSlot.slotToString(slot) + ", value=" + value + '}';
      }
    }

    /** Compile an expression and return a register containing the result. */
    private CompileExpressionResult compileExpression(Expression expression) {
      if (expression instanceof Identifier) {
        return compileGet((Identifier) expression);
      } else if (expression instanceof StringLiteral) {
        return compileConstant(((StringLiteral) expression).getValue());
      } else if (expression instanceof IntLiteral) {
        return compileIntLiteral((IntLiteral) expression);
      } else if (expression instanceof FloatLiteral) {
        return compileConstant(StarlarkFloat.of(((FloatLiteral) expression).getValue()));
      } else {
        return compileExpressionTo(expression, BcSlot.ANY_FLAG);
      }
    }

    private CompileExpressionResult compileIndex(IndexExpression expression, int result) {
      if (result == BcSlot.ANY_FLAG) {
        result = allocSlot();
      }
      int object = compileExpression(expression.getObject()).slot;
      int key = compileExpression(expression.getKey()).slot;
      write(BcInstr.Opcode.INDEX, expression, object, key, result);
      return new CompileExpressionResult(result, null);
    }

    private CompileExpressionResult compileDot(DotExpression dotExpression, int result) {
      SavedState saved = save();

      CompileExpressionResult object = compileExpression(dotExpression.getObject());

      if (object.value instanceof Structure) {
        try {
          // TODO(nga): this invocation is generally incorrect, but
          //    * all structs in Buck are pure
          //    * in Buck we only use default semantics
          Object attrValue = Starlark
              .getattr(Mutability.IMMUTABLE, StarlarkSemantics.DEFAULT, object.value,
                  dotExpression.getField().getName(), null);
          if (attrValue != null) {
            saved.reset();
            return compileConstantTo(dotExpression, attrValue, result);
          }
        } catch (EvalException | InterruptedException e) {
          // ignore
        }
      }

      if (result == BcSlot.ANY_FLAG) {
        result = allocSlot();
      }
      write(
          BcInstr.Opcode.DOT,
          dotExpression,
          object.slot,
          allocString(dotExpression.getField().getName()),
          result);
      return new CompileExpressionResult(result, null);
    }

    private CompileExpressionResult compileSliceExpression(SliceExpression slice, int result) {
      int object = compileExpression(slice.getObject()).slot;

      int start = slice.getStart() != null ? compileExpression(slice.getStart()).slot : BcSlot.NULL_FLAG;
      int stop = slice.getStop() != null ? compileExpression(slice.getStop()).slot : BcSlot.NULL_FLAG;
      int step = slice.getStep() != null ? compileExpression(slice.getStep()).slot : BcSlot.NULL_FLAG;

      if (result == BcSlot.ANY_FLAG) {
        result = allocSlot();
      }
      write(BcInstr.Opcode.SLICE, slice, object, start, stop, step, result);
      return new CompileExpressionResult(result, null);
    }

    private CompileExpressionResult compileGet(Identifier identifier) {
      Resolver.Binding binding = identifier.getBinding();
      if (binding == null) {
        throw new RuntimeException("identifier.binding is null");
      }
      switch (binding.getScope()) {
        case LOCAL:
          return new CompileExpressionResult(binding.getIndex() | BcSlot.LOCAL_FLAG, null);
        case GLOBAL:
          int globalVarIndex = this.globalIndex[binding.getIndex()];
          if (!binding.isFirstReassignable()) {
            Object globalValue = module.getGlobalByIndex(globalVarIndex);
            if (globalValue != null) {
              return compileConstant(globalValue);
            }
          }
          return new CompileExpressionResult(BcSlot.global(globalVarIndex), null);
        case FREE:
          if (!binding.isFirstReassignable()) {
            StarlarkFunction.Cell cell = (StarlarkFunction.Cell) freevars.get(binding.getIndex());
            if (cell.x != null) {
              return compileConstant(cell.x);
            }
          }
          return new CompileExpressionResult(
              binding.getIndex() | BcSlot.FREE_FLAG,
              null);
        case CELL:
          return new CompileExpressionResult(
              binding.getIndex() | BcSlot.CELL_FLAG,
              null
          );
        case UNIVERSAL:
          return compileConstant(Starlark.UNIVERSE_OBJECTS.valueByIndex(binding.getIndex()));
        case PREDECLARED:
          return compileConstant(module.getPredeclared(binding.getName()));
        default:
          throw new IllegalStateException();
      }
    }

    private CompileExpressionResult compileComprehension(Comprehension comprehension, int result) {
      int temp = allocSlot();
      if (comprehension.isDict()) {
        write(BcInstr.Opcode.DICT, comprehension.getBody(), 0, temp);
      } else {
        write(BcInstr.Opcode.LIST, comprehension.getBody(), 0, temp);
      }

      // The Lambda class serves as a recursive lambda closure.
      class Lambda {
        // execClauses(index) recursively compiles the clauses starting at index,
        // and finally compiles the body and adds its value to the result.
        private void compileClauses(int index) {
          // recursive case: one or more clauses
          if (index != comprehension.getClauses().size()) {
            Comprehension.Clause clause = comprehension.getClauses().get(index);
            if (clause instanceof Comprehension.For) {
              compileFor(
                  ((Comprehension.For) clause).getVars(),
                  ((Comprehension.For) clause).getIterable(),
                  () -> compileClauses(index + 1));
            } else if (clause instanceof Comprehension.If) {
              CompileExpressionResult cond = compileExpression(((Comprehension.If) clause).getCondition());
              // TODO: optimize if cond != null
              int end = writeForwardCondJump(BcInstr.Opcode.IF_NOT_BR, clause, cond.slot);
              compileClauses(index + 1);
              patchForwardJump(end);
            } else {
              throw new IllegalStateException("unknown compr clause: " + clause);
            }
          } else {
            if (comprehension.isDict()) {
              DictExpression.Entry entry = (DictExpression.Entry) comprehension.getBody();
              int key = compileExpression(entry.getKey()).slot;
              int value = compileExpression(entry.getValue()).slot;
              write(BcInstr.Opcode.SET_INDEX, entry, temp, key, value);
            } else {
              int value = compileExpression((Expression) comprehension.getBody()).slot;
              write(BcInstr.Opcode.LIST_APPEND, comprehension.getBody(), temp, value);
            }
          }
        }
      }

      new Lambda().compileClauses(0);
      if (result == BcSlot.ANY_FLAG) {
        return new CompileExpressionResult(temp, null);
      } else {
        cp(comprehension, temp, result);
        return new CompileExpressionResult(result, null);
      }
    }

    private CompileExpressionResult compileDict(DictExpression dictExpression, int result) {
      if (result == BcSlot.ANY_FLAG) {
        result = allocSlot();
      }

      int[] args = new int[1 + dictExpression.getEntries().size() * 2 + 1];
      int i = 0;
      args[i++] = dictExpression.getEntries().size();
      for (DictExpression.Entry entry : dictExpression.getEntries()) {
        args[i++] = compileExpression(entry.getKey()).slot;
        args[i++] = compileExpression(entry.getValue()).slot;
      }
      args[i++] = result;
      Preconditions.checkState(i == args.length);

      write(BcInstr.Opcode.DICT, dictExpression, args);

      return new CompileExpressionResult(result, null);
    }

    private CompileExpressionResult compileList(ListExpression listExpression, int result) {
      if (result == BcSlot.ANY_FLAG) {
        result = allocSlot();
      }

      int[] args = new int[1 + listExpression.getElements().size() + 1];
      int i = 0;
      args[i++] = listExpression.getElements().size();
      for (Expression element : listExpression.getElements()) {
        args[i++] = compileExpression(element).slot;
      }
      args[i++] = result;
      Preconditions.checkState(i == args.length);
      write(
          listExpression.isTuple() ? BcInstr.Opcode.TUPLE : BcInstr.Opcode.LIST,
          listExpression,
          args);
      return new CompileExpressionResult(result, null);
    }

    private CompileExpressionResult compileConditional(ConditionalExpression conditionalExpression, int result) {
      if (result == BcSlot.ANY_FLAG) {
        result = allocSlot();
      }

      int cond = compileExpression(conditionalExpression.getCondition()).slot;
      int thenAddr = writeForwardCondJump(BcInstr.Opcode.IF_NOT_BR, conditionalExpression, cond);
      compileExpressionTo(conditionalExpression.getThenCase(), result);
      int end = writeForwardJump(conditionalExpression);
      patchForwardJump(thenAddr);
      compileExpressionTo(conditionalExpression.getElseCase(), result);
      patchForwardJump(end);

      return new CompileExpressionResult(result, null);
    }

    private CompileExpressionResult compileCall(CallExpression callExpression, int result) {
      if (result == BcSlot.ANY_FLAG) {
        result = allocSlot();
      }

      ArrayList<Argument.Positional> positionals = new ArrayList<>();
      ArrayList<Argument.Keyword> nameds = new ArrayList<>();
      Argument.Star star = null;
      Argument.StarStar starStar = null;
      for (Argument argument : callExpression.getArguments()) {
        if (argument instanceof Argument.Positional) {
          positionals.add((Argument.Positional) argument);
        } else if (argument instanceof Argument.Keyword) {
          nameds.add((Argument.Keyword) argument);
        } else if (argument instanceof Argument.Star) {
          star = (Argument.Star) argument;
        } else if (argument instanceof Argument.StarStar) {
          starStar = (Argument.StarStar) argument;
        } else {
          throw new IllegalStateException();
        }
      }
      int numCallArgs = 2; // lparen + fn
      numCallArgs += 1 + positionals.size();
      numCallArgs += 1 + (2 * nameds.size());
      numCallArgs += 3; // star, star-star, result
      int[] args = new int[numCallArgs];

      int i = 0;
      args[i++] = allocObject(callExpression.getLparenLocation());
      CompileExpressionResult function = compileExpression(callExpression.getFunction());

      args[i++] = function.slot;

      args[i++] = positionals.size();
      for (Argument.Positional positional : positionals) {
        args[i++] = compileExpression(positional.getValue()).slot;
      }
      args[i++] = nameds.size();
      for (Argument.Keyword named : nameds) {
        args[i++] = allocString(named.getName());
        args[i++] = compileExpression(named.getValue()).slot;
      }
      args[i++] = star != null ? compileExpression(star.getValue()).slot : BcSlot.NULL_FLAG;
      args[i++] = starStar != null ? compileExpression(starStar.getValue()).slot : BcSlot.NULL_FLAG;

      args[i++] = result;

      Preconditions.checkState(i == args.length);

      write(BcInstr.Opcode.CALL, callExpression, args);

      return new CompileExpressionResult(result, null);
    }

    private CompileExpressionResult compileUnaryOperator(UnaryOperatorExpression expression, int result) {
      if (result == BcSlot.ANY_FLAG) {
        result = allocSlot();
      }
      CompileExpressionResult value = compileExpression(expression.getX());
      if (expression.getOperator() == TokenKind.NOT) {
        write(BcInstr.Opcode.NOT, expression, value.slot, result);
      } else {
        write(
            BcInstr.Opcode.UNARY,
            expression,
            value.slot,
            expression.getOperator().ordinal(),
            result);
      }
      return new CompileExpressionResult(result, null);
    }

    private CompileExpressionResult compileBinaryOperator(BinaryOperatorExpression expression, int result) {
      switch (expression.getOperator()) {
        case AND:
        case OR:
          {
            if (result == BcSlot.ANY_FLAG) {
              result = allocSlot();
            }

            BcInstr.Opcode opcode =
                expression.getOperator() == TokenKind.AND ? BcInstr.Opcode.IF_NOT_BR : BcInstr.Opcode.IF_BR;

            int lhs = compileExpression(expression.getX()).slot;
            int elseMark = writeForwardCondJump(opcode, expression, lhs);
            compileExpressionTo(expression.getY(), result);
            int end = writeForwardJump(expression);
            patchForwardJump(elseMark);
            cp(expression, lhs, result);
            patchForwardJump(end);
            return new CompileExpressionResult(result, null);
          }
        default:
          return compileBinaryOperatorNonShortCicrcuiting(expression, result);
      }
    }

    private CompileExpressionResult compileBinaryOperatorNonShortCicrcuiting(
        BinaryOperatorExpression expression, int result) {
      if (result == BcSlot.ANY_FLAG) {
        result = allocSlot();
      }

      CompileExpressionResult x = compileExpression(expression.getX());
      CompileExpressionResult y = compileExpression(expression.getY());
      switch (expression.getOperator()) {
        case EQUALS_EQUALS:
          write(BcInstr.Opcode.EQ, expression, x.slot, y.slot, result);
          return new CompileExpressionResult(result, null);
        case NOT_EQUALS:
          write(BcInstr.Opcode.NOT_EQ, expression, x.slot, y.slot, result);
          return new CompileExpressionResult(result, null);
        default:
          write(
              BcInstr.Opcode.BINARY,
              expression,
              x.slot,
              y.slot,
              expression.getOperator().ordinal(),
              result);
          return new CompileExpressionResult(result, null);
      }
    }

    Compiled finish() {
      ImmutableMap.Builder<Integer, Node> instrToNode = ImmutableMap.builder();
      for (int i = 0; i != this.instrToNode.size(); i += 2) {
        instrToNode.put((int) this.instrToNode.get(i), (Node) this.instrToNode.get(i + 1));
      }

      return new Compiled(
          rfn,
          module,
          strings.toArray(ArraysForStarlark.EMPTY_STRING_ARRAY),
          objects.toArray(ArraysForStarlark.EMPTY_OBJECT_ARRAY),
          Arrays.copyOf(text, ip),
          maxSlots,
          constSlots.toArray(ArraysForStarlark.EMPTY_OBJECT_ARRAY),
          maxLoopDepth,
          instrToNode.build());
    }
  }

  public static Compiled compileFunction(Resolver.Function rfn, Module module, int[] globalIndex,
      Tuple freevars) {
    Compiler compiler = new Compiler(rfn, module, globalIndex, freevars);
    compiler.compileStatements(rfn.getBody(), true);
    return compiler.finish();
  }
}
