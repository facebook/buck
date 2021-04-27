package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
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
import net.starlark.java.syntax.FileLocations;
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

  /**
   * The compiler implementation.
   */
  static class Compiler {
    final BcWriter bcWriter;
    private final FileLocations fileLocations;
    private final StarlarkThread thread;
    private final Module module;
    private final Tuple freevars;

    private Compiler(StarlarkThread thread, Resolver.Function rfn, Module module, Tuple freevars) {
      Preconditions.checkArgument(rfn.getModule() == module.getResolverModule(),
          "must compile function with the same module used to resolve function,"
              + " otherwise global indices won't match");

      this.fileLocations = rfn.getFileLocations();
      this.thread = thread;
      this.module = module;
      this.freevars = freevars;
      this.bcWriter = new BcWriter(fileLocations, module, rfn.getName(),
          rfn.getLocals(), rfn.getFreeVars());
    }

    private BcWriter.LocOffset nodeToLocOffset(Node node) {
      Preconditions.checkState(node.getLocs() == fileLocations,
          "node does not share the same file locations as the rest of the function");
      if (node instanceof BinaryOperatorExpression) {
        return new BcWriter.LocOffset(((BinaryOperatorExpression) node).getOpOffset());
      } else if (node instanceof IndexExpression) {
        return new BcWriter.LocOffset(((IndexExpression) node).getLbracketOffset());
      } else if (node instanceof SliceExpression) {
        return new BcWriter.LocOffset(((SliceExpression) node).getLbracketOffset());
      } else if (node instanceof DotExpression) {
        return new BcWriter.LocOffset(((DotExpression) node).getDotOffset());
      } else if (node instanceof AssignmentStatement) {
        return new BcWriter.LocOffset(((AssignmentStatement) node).getOpOffset());
      } else {
        return new BcWriter.LocOffset(node.getStartOffset());
      }
    }

    /** Write complete opcode with validation. */
    private void write(BcInstr.Opcode opcode, Node node, int... args) {
      BcWriter.LocOffset locOffset = nodeToLocOffset(node);
      bcWriter.write(opcode, locOffset, args);
    }

    /** Write complete opcode, allocating out slot if it is {@link BcSlot#ANY_FLAG}. */
    private CompileExpressionResult writeToOut(BcInstr.Opcode opcode, Node node, int[] args, int out) {
      if (out == BcSlot.ANY_FLAG) {
        out = bcWriter.allocSlot();
      } else {
        BcSlot.checkLocal(out);
      }
      write(opcode, node, BcWriter.args(args, out));
      return new CompileExpressionResult(out, null);
    }

    private void cp(Node node, int from, int to) {
      // Sanity check preconditions

      BcSlot.checkValidSourceSlot(from);
      if ((from & BcSlot.MASK) == BcSlot.LOCAL_FLAG) {
        Preconditions.checkArgument((from & ~BcSlot.MASK) < bcWriter.getSlots());
      }

      BcSlot.checkLocal(to);
      Preconditions.checkArgument((to & ~BcSlot.MASK) < bcWriter.getSlots());

      // This optimizes away assignment `x = x`
      if (from == to) {
        return;
      }

      BcInstr.Opcode opcode = BcSlot.isLocal(from) ? BcInstr.Opcode.CP_LOCAL :  BcInstr.Opcode.CP;
      write(opcode, node, from, to);
    }

    /**
     * Write forward condition jump instruction. Return an address to be patched when the jump
     * address is known.
     */
    int writeForwardCondJump(BcInstr.Opcode opcode, Node expression, int cond) {
      Preconditions.checkState(
          opcode == BcInstr.Opcode.IF_BR_LOCAL || opcode == BcInstr.Opcode.IF_NOT_BR_LOCAL);
      int condLocal;
      if (!BcSlot.isLocal(cond)) {
        condLocal = bcWriter.allocSlot();
        cp(expression, cond, condLocal);
      } else {
        condLocal = cond;
      }
      return bcWriter.writeForwardCondJump(opcode, nodeToLocOffset(expression), condLocal);
    }

    /**
     * Write unconditional forward jump. Return an address to be patched when the jump address is
     * known.
     */
    int writeForwardJump(Node expression) {
      BcWriter.LocOffset locOffset = nodeToLocOffset(expression);
      return bcWriter.writeForwardJump(locOffset);
    }

    /** Compile. */
    void compileStatements(List<Statement> statements, boolean postAssignHook) {
      for (Statement statement : statements) {
        compileStatement(statement, postAssignHook);
      }
    }

    private void compileStatement(Statement statement, boolean postAssignHook) {
      // No registers are shared across statements.
      // We could implement precise register tracking, but there is no need for that at the moment.
      bcWriter.decallocateAllSlots();

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
      write(BcInstr.Opcode.LOAD_STMT, loadStatement, bcWriter.allocObject(loadStatement));
    }

    private void compileDefStatement(DefStatement def) {
      int result = bcWriter.allocSlot();
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

      int nparams = rfn.numNonStarParams();

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
      args[i++] = bcWriter.allocObject(rfn);
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
      new BcCompilerForIf(this).compileIfStatement(ifStatement);
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
      BcWriter.LocOffset locOffset = nodeToLocOffset(node);
      bcWriter.writeContinue(locOffset);
    }

    private void compileBreak(Node node) {
      BcWriter.LocOffset locOffset = nodeToLocOffset(node);
      bcWriter.writeBreak(locOffset);
    }

    /** Callback invoked to compile the loop body. */
    private interface ForBody {
      void compile();
    }

    /** Generic compile for loop routine, used in for statement and in loop comprehension. */
    private void compileFor(Expression vars, Expression collection, ForBody body) {
      CompileExpressionResult iterable = compileExpression(collection);

      // Most common loops are single var loops, we don't need to use temporary variables.
      boolean assignStraightToLocal = vars instanceof Identifier
          && ((Identifier) vars).getBinding().getScope() == Resolver.Scope.LOCAL;

      // Register where we are storing the next iterator value.
      // This register is update by FOR_INIT and CONTINUE instructions.
      int nextValueSlot = assignStraightToLocal ?
          localIdentSlot((Identifier) vars) : bcWriter.allocSlot();

      bcWriter.writeForInit(nodeToLocOffset(collection), iterable.slot, nextValueSlot);

      if (!assignStraightToLocal) {
        compileAssignment(nextValueSlot, vars, false);
      }

      body.compile();

      // We use usual CONTINUE statement in the end of the loop.
      // Note: CONTINUE does unnecessary goto e in the end of iteration.
      compileContinue(collection);

      bcWriter.writeForClose();
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
        if (lhsIdent.getBinding().getScope() == Resolver.Scope.LOCAL) {
          compileExpressionTo(assignmentStatement.getRHS(), localIdentSlot(lhsIdent));
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
      int[] args = new int[2 + list.getElements().size()];
      args[0] = rhs;
      args[1] = list.getElements().size();

      IntArrayBuilder postUnpackAssignmentSlots = new IntArrayBuilder();
      ArrayList<Expression> postUnpackAssignmentExprs = new ArrayList<>();

      int i = 2;
      for (Expression element : list.getElements()) {
        if (element instanceof Identifier
            && ((Identifier) element).getBinding().getScope() == Resolver.Scope.LOCAL) {
          args[i++] = localIdentSlot((Identifier) element);
        } else {
          int slot = bcWriter.allocSlot();
          postUnpackAssignmentSlots.add(slot);
          postUnpackAssignmentExprs.add(element);
          args[i++] = slot;
        }
      }

      Preconditions.checkState(i == args.length);

      write(BcInstr.Opcode.UNPACK, list, args);

      Preconditions.checkState(
          postUnpackAssignmentSlots.size() == postUnpackAssignmentExprs.size());

      for (int j = 0; j < postUnpackAssignmentSlots.size(); ++j) {
        compileAssignment(
            postUnpackAssignmentSlots.get(j),
            postUnpackAssignmentExprs.get(j),
            postAssignHook);
      }
    }

    private void compileSet(int rhs, Identifier identifier, boolean postAssignHook) {
      Resolver.Binding binding = identifier.getBinding();
      switch (binding.getScope()) {
        case LOCAL:
          cp(identifier, rhs, binding.getIndex());
          return;
        case GLOBAL:
          write(
              BcInstr.Opcode.SET_GLOBAL,
              identifier,
              rhs,
              binding.getIndex(),
              bcWriter.allocString(identifier.getName()),
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
      BcWriter.LocOffset locOffset = nodeToLocOffset(node);
      bcWriter.writeThrowException(locOffset, message);
    }

    private void compileAgumentedAssignmentToIdentifier(AssignmentStatement assignmentStatement) {
      Identifier lhs = (Identifier) assignmentStatement.getLHS();

      AugmentedAssignmentRhs rhs = compileAugmentedAssignmentRhs(
          assignmentStatement.getOperator(),
          assignmentStatement.getRHS());

      if (lhs.getBinding().getScope() == Resolver.Scope.LOCAL) {
        writeBinaryInPlace(
            assignmentStatement,
            localIdentSlot(lhs),
            rhs,
            localIdentSlot(lhs)
        );
      } else {
        CompileExpressionResult value = compileGet(lhs);
        int temp = bcWriter.allocSlot();
        writeBinaryInPlace(
            assignmentStatement,
            value.slot,
            rhs,
            temp);
        compileSet(temp, lhs, false);
      }
    }

    /** Result of compilation of {@code x (op)= y} rhs. */
    private static class AugmentedAssignmentRhs {
      /** When operator is {@code +=} and RHS is {@code [...]}. */
      @Nullable private final CompileExpressionListResult listResult;
      /** All other values. */
      @Nullable
      private final CompileExpressionResult defaultResult;

      public AugmentedAssignmentRhs(
          CompileExpressionListResult listResult) {
        this.listResult = listResult;
        this.defaultResult = null;
      }

      public AugmentedAssignmentRhs(
          CompileExpressionResult defaultResult) {
        this.defaultResult = defaultResult;
        this.listResult = null;
      }
    }

    private AugmentedAssignmentRhs compileAugmentedAssignmentRhs(TokenKind op, Expression rhs) {
      if (op == TokenKind.PLUS
          && rhs instanceof ListExpression
          && !((ListExpression) rhs).isTuple()) {
        return new AugmentedAssignmentRhs(
            compileExpressionList(((ListExpression) rhs).getElements()));
      } else {
        return new AugmentedAssignmentRhs(compileExpression(rhs));
      }
    }

    private void writeBinaryInPlace(
        AssignmentStatement assignmentStatement, int lhs, AugmentedAssignmentRhs rhs, int result) {
      if (assignmentStatement.getOperator() == TokenKind.PLUS) {
        // The only operator supporting binary in place is plus for lists
        if (rhs.listResult != null) {
          writeToOut(
              BcInstr.Opcode.PLUS_LIST_IN_PLACE,
              assignmentStatement,
              BcWriter.args(lhs, rhs.listResult.opcodeArgs),
              result);
        } else {
          Preconditions.checkState(rhs.defaultResult != null);
          BcInstr.Opcode opcode;
          if (rhs.defaultResult.value instanceof String) {
            opcode = BcInstr.Opcode.PLUS_STRING_IN_PLACE;
          } else {
            opcode = BcInstr.Opcode.PLUS_IN_PLACE;
          }
          write(opcode, assignmentStatement, lhs, rhs.defaultResult.slot, result);
        }
      } else {
        // Otherwise inplace is equivalent to `lhs = lhs + rhs`.
        writeBinaryOp(assignmentStatement, assignmentStatement.getOperator(),
            new CompileExpressionResult(lhs, null), rhs.defaultResult, result);
      }
    }

    private void compileAgumentedAssignment(AssignmentStatement assignmentStatement) {
      Preconditions.checkState(assignmentStatement.getOperator() != null);
      if (assignmentStatement.getLHS() instanceof Identifier) {
        compileAgumentedAssignmentToIdentifier(assignmentStatement);
      } else if (assignmentStatement.getLHS() instanceof IndexExpression) {
        IndexExpression indexExpression = (IndexExpression) assignmentStatement.getLHS();

        int object = compileExpression(indexExpression.getObject()).slot;
        int key = compileExpression(indexExpression.getKey()).slot;
        AugmentedAssignmentRhs rhs = compileAugmentedAssignmentRhs(
            assignmentStatement.getOperator(),
            assignmentStatement.getRHS());
        int temp = bcWriter.allocSlot();
        write(BcInstr.Opcode.INDEX, assignmentStatement, object, key, temp);
        writeBinaryInPlace(assignmentStatement, temp, rhs, temp);
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
      Starlark.checkValid(constant);
      int slot = bcWriter.allowConstSlot(constant);
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
        result = bcWriter.allocSlot();
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

    static class CompileExpressionResult {
      final int slot;
      @Nullable
      final Object value;

      private CompileExpressionResult(int slot, @Nullable Object value) {
        BcSlot.checkValidSourceSlot(slot);
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
    CompileExpressionResult compileExpression(Expression expression) {
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
        result = bcWriter.allocSlot();
      }
      int object = compileExpression(expression.getObject()).slot;
      int key = compileExpression(expression.getKey()).slot;
      write(BcInstr.Opcode.INDEX, expression, object, key, result);
      return new CompileExpressionResult(result, null);
    }

    private CompileExpressionResult compileDot(DotExpression dotExpression, int result) {
      BcWriter.SavedState saved = bcWriter.save();

      CompileExpressionResult object = compileExpression(dotExpression.getObject());

      if (object.value != null) {
        try {
          // This code is correct because for all known objects
          // `getattr` produces the same instance for given `attr`.
          // When it is no longer the case, we can add something like
          // `ImmutableStructure` interface.
          Object attrValue =
              Starlark.getattr(
                  Mutability.IMMUTABLE,
                  thread.getSemantics(),
                  object.value,
                  dotExpression.getField().getName(),
                  null);
          if (attrValue != null) {
            saved.reset();
            return compileConstantTo(dotExpression, attrValue, result);
          }
        } catch (EvalException | InterruptedException e) {
          // ignore
        }
      }

      if (result == BcSlot.ANY_FLAG) {
        result = bcWriter.allocSlot();
      }
      write(
          BcInstr.Opcode.DOT,
          dotExpression,
          object.slot,
          bcWriter.allocString(dotExpression.getField().getName()),
          result);
      return new CompileExpressionResult(result, null);
    }

    private CompileExpressionResult compileSliceExpression(SliceExpression slice, int result) {
      int object = compileExpression(slice.getObject()).slot;

      int start = slice.getStart() != null ? compileExpression(slice.getStart()).slot : BcSlot.NULL_FLAG;
      int stop = slice.getStop() != null ? compileExpression(slice.getStop()).slot : BcSlot.NULL_FLAG;
      int step = slice.getStep() != null ? compileExpression(slice.getStep()).slot : BcSlot.NULL_FLAG;

      if (result == BcSlot.ANY_FLAG) {
        result = bcWriter.allocSlot();
      }
      write(BcInstr.Opcode.SLICE, slice, object, start, stop, step, result);
      return new CompileExpressionResult(result, null);
    }

    private int localIdentSlot(Identifier identifier) {
      Preconditions.checkArgument(identifier.getBinding().getScope() == Resolver.Scope.LOCAL);
      return BcSlot.local(identifier.getBinding().getIndex());
    }

    private CompileExpressionResult compileGet(Identifier identifier) {
      Resolver.Binding binding = identifier.getBinding();
      if (binding == null) {
        throw new RuntimeException("identifier.binding is null");
      }
      switch (binding.getScope()) {
        case LOCAL:
          return new CompileExpressionResult(localIdentSlot(identifier), null);
        case GLOBAL:
          int globalVarIndex = binding.getIndex();
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
          return compileConstant(module.getResolverModule().getPredeclared(binding.getName()));
        default:
          throw new IllegalStateException();
      }
    }

    private CompileExpressionResult compileComprehension(Comprehension comprehension, int result) {
      // Must explicitly use temporary variable, because comprehension expression
      // may reference to the same slot we are about to write.
      int temp = bcWriter.allocSlot();
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
              int end = writeForwardCondJump(BcInstr.Opcode.IF_NOT_BR_LOCAL, clause, cond.slot);
              compileClauses(index + 1);
              bcWriter.patchForwardJump(end);
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
        result = bcWriter.allocSlot();
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
      BcWriter.SavedState saved = bcWriter.save();

      CompileExpressionListResult elements = compileExpressionList(
          listExpression.getElements());

      if (elements.constants != null) {
        if (listExpression.isTuple()) {
          saved.reset();
          return compileConstantTo(listExpression, Tuple.wrap(elements.constants), result);
        }
      }

      return writeToOut(
          listExpression.isTuple() ? BcInstr.Opcode.TUPLE : BcInstr.Opcode.LIST,
          listExpression,
          elements.opcodeArgs,
          result);
    }

    /**
     * Is truth property of this object will never change.
     *
     * For example, truth of any tuple is immutable. Truth of list
     * is immutable only if the list is immutable.
     */
    static boolean isTruthImmutable(Object o) {
      if (Starlark.isImmutable(o)) {
        return true;
      }
      // Structure and Tuple may have mutable content,
      // but their truth is immutable.
      if (o instanceof Structure || o instanceof Tuple) {
        return true;
      }
      return false;
    }

    private CompileExpressionResult compileConditional(ConditionalExpression conditionalExpression, int result) {
      BcWriter.SavedState saved = bcWriter.save();

      CompileExpressionResult cond = compileExpression(
          conditionalExpression.getCondition());
      if (cond.value != null && isTruthImmutable(cond.value)) {
        saved.reset();
        if (Starlark.truth(cond.value)) {
          return compileExpressionTo(conditionalExpression.getThenCase(), result);
        } else {
          return compileExpressionTo(conditionalExpression.getElseCase(), result);
        }
      }

      if (result == BcSlot.ANY_FLAG) {
        result = bcWriter.allocSlot();
      }

      int thenAddr = writeForwardCondJump(BcInstr.Opcode.IF_NOT_BR_LOCAL, conditionalExpression,
          cond.slot);
      compileExpressionTo(conditionalExpression.getThenCase(), result);
      int end = writeForwardJump(conditionalExpression);
      bcWriter.patchForwardJump(thenAddr);
      compileExpressionTo(conditionalExpression.getElseCase(), result);
      bcWriter.patchForwardJump(end);

      return new CompileExpressionResult(result, null);
    }

    private CompileExpressionResult compileCallLinked(
        StarlarkCallable callable, StarlarkCallableLinkSig linkSig,
        CallExpression callExpression, int result) {
      BcWriter.SavedState saved = bcWriter.save();

      Argument.Star star = null;
      Argument.StarStar starStar = null;
      int p = callExpression.getArguments().size();
      if (p > 0 && callExpression.getArguments().get(p - 1) instanceof Argument.StarStar) {
        starStar = (Argument.StarStar) callExpression.getArguments().get(--p);
      }
      if (p > 0 && callExpression.getArguments().get(p - 1) instanceof Argument.Star) {
        star = (Argument.Star) callExpression.getArguments().get(--p);
      }
      ImmutableList<Expression> regArgs = callExpression
          .getArguments()
          .subList(0, p)
          .stream()
          .map(Argument::getValue)
          .collect(ImmutableList.toImmutableList());

      StarlarkCallableLinked fn = callable.linkCall(linkSig);

      int lparen = bcWriter.allocObject(BcCallLocs.forExpression(callExpression));
      int fnSlot = bcWriter.allocObject(fn);

      CompileExpressionListResult regArgsResult = compileExpressionList(regArgs);

      int starSlot;
      int starStarSlot;
      if (star != null) {
        starSlot = compileExpression(star.getValue()).slot;
      } else {
        starSlot = BcSlot.NULL_FLAG;
      }
      if (starStar != null) {
        starStarSlot = compileExpression(starStar.getValue()).slot;
      } else {
        starStarSlot = BcSlot.NULL_FLAG;
      }

      boolean functionIsSpeculativeSafe = callable instanceof BuiltinFunction
          && ((BuiltinFunction) callable).isSpeculativeSafe();
      if (functionIsSpeculativeSafe
          && !linkSig.hasStars()
          && regArgsResult.constants != null
          && regArgsResult.allConstantsImmutable()) {
        try {
          Object specCallResult = callable
              .linkAndCall(linkSig, thread, regArgsResult.constants, null, null);
          if (Starlark.isImmutable(specCallResult)) {
            saved.reset();
            return compileConstantTo(callExpression, specCallResult, result);
          }
        } catch (EvalException | InterruptedException e) {
          // ignore
        }
      }

      // Only inline no-argument calls to no-parameter functions, otherwise
      // it's quite hard to correctly detect that function call won't fail at runtime.
      // Consider this example:
      // ```
      // def bar(a):
      //   # This function could be inlinable as constant
      //   return None
      // def foo():
      //   # If this call inlined as constant,
      //   # we need to report `x` accessed before initialization
      //   bar(x)
      //   x = 1
      // ```
      if (callable instanceof StarlarkFunction && linkSig == StarlarkCallableLinkSig.positional(0)) {
        Object constResult = ((StarlarkFunction) callable).returnsConst();
        if (constResult != null && ((StarlarkFunction) callable).getParameterNames().isEmpty()) {
          saved.reset();
          return compileConstantTo(callExpression, constResult, result);
        }
      }

      int[] newArgs = BcWriter.args(
          new int[] { lparen, fnSlot },
          regArgsResult.opcodeArgs,
          new int[] { starSlot, starStarSlot });
      return writeToOut(BcInstr.Opcode.CALL_LINKED, callExpression, newArgs, result);
    }

    private CompileExpressionResult compileCall(CallExpression callExpression, int result) {
      BcWriter.SavedState saved = bcWriter.save();

      ArrayList<String> argNames = new ArrayList<>();
      ArrayList<Expression> regArgs = new ArrayList<>();
      Argument.Star star = null;
      Argument.StarStar starStar = null;
      for (Argument argument : callExpression.getArguments()) {
        if (argument instanceof Argument.Positional) {
          regArgs.add(argument.getValue());
        } else if (argument instanceof Argument.Keyword) {
          argNames.add(argument.getName());
          regArgs.add(argument.getValue());
        } else if (argument instanceof Argument.Star) {
          Preconditions.checkState(star == null);
          star = (Argument.Star) argument;
        } else if (argument instanceof Argument.StarStar) {
          Preconditions.checkState(starStar == null);
          starStar = (Argument.StarStar) argument;
        } else {
          throw new IllegalStateException();
        }
      }

      CompileExpressionResult function = compileExpression(callExpression.getFunction());

      StarlarkCallableLinkSig linkSig = StarlarkCallableLinkSig.of(
          regArgs.size() - argNames.size(),
          argNames.toArray(ArraysForStarlark.EMPTY_STRING_ARRAY),
          star != null,
          starStar != null);

      if (function.value instanceof StarlarkCallable) {
        saved.reset();
        return compileCallLinked((StarlarkCallable) function.value, linkSig, callExpression, result);
      }

      int lparen = bcWriter.allocObject(BcCallLocs.forExpression(callExpression));
      int fn = function.slot;
      int sig = bcWriter.allocObject(new BcDynCallSite(linkSig));

      int[] regArgsOpcodes = compileExpressionList(regArgs).opcodeArgs;

      int starSlot;
      int starStarSlot;
      if (star != null) {
        starSlot = compileExpression(star.getValue()).slot;
      } else {
        starSlot = BcSlot.NULL_FLAG;
      }
      if (starStar != null) {
        starStarSlot = compileExpression(starStar.getValue()).slot;
      } else {
        starStarSlot = BcSlot.NULL_FLAG;
      }

      int[] newArgs = BcWriter.args(
          new int[] { lparen, fn, sig },
          regArgsOpcodes,
          new int[] { starSlot, starStarSlot });

      return writeToOut(BcInstr.Opcode.CALL, callExpression, newArgs, result);
    }

    private CompileExpressionResult compileUnaryOperator(UnaryOperatorExpression expression, int result) {
      BcWriter.SavedState saved = bcWriter.save();

      CompileExpressionResult value = compileExpression(expression.getX());

      if (expression.getOperator() == TokenKind.NOT && value.value != null && isTruthImmutable(value.value)) {
        saved.reset();
        return compileConstantTo(expression, !Starlark.truth(value.value), result);
      }

      if (result == BcSlot.ANY_FLAG) {
        result = bcWriter.allocSlot();
      }
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
            BcInstr.Opcode opcode =
                expression.getOperator() == TokenKind.AND
                    ? BcInstr.Opcode.IF_NOT_BR_LOCAL
                    : BcInstr.Opcode.IF_BR_LOCAL;

            BcWriter.SavedState saved = bcWriter.save();
            CompileExpressionResult lhs = compileExpression(expression.getX());
            if (lhs.value != null && isTruthImmutable(lhs.value)) {
              saved.reset();
              if (Starlark.truth(lhs.value) != (expression.getOperator() == TokenKind.AND)) {
                return compileConstantTo(expression, lhs.value, result);
              } else {
                return compileExpressionTo(expression.getY(), result);
              }
            }

            if (result == BcSlot.ANY_FLAG) {
              result = bcWriter.allocSlot();
            }

            int elseMark = writeForwardCondJump(opcode, expression, lhs.slot);
            compileExpressionTo(expression.getY(), result);
            int end = writeForwardJump(expression);
            bcWriter.patchForwardJump(elseMark);
            cp(expression, lhs.slot, result);
            bcWriter.patchForwardJump(end);
            return new CompileExpressionResult(result, null);
          }
        default:
          return compileBinaryOperatorNonShortCicrcuiting(expression, result);
      }
    }

    private static class CompileExpressionListResult {
      private final int[] opcodeArgs;
      @Nullable
      private final Object[] constants;

      CompileExpressionListResult(int[] opcodeArgs, @Nullable Object[] constants) {
        this.opcodeArgs = opcodeArgs;
        this.constants = constants;
      }

      boolean allConstantsImmutable() {
        if (constants == null) {
          return false;
        }
        for (Object constant : constants) {
          if (!Starlark.isImmutable(constant)) {
            return false;
          }
        }
        return true;
      }
    }

    private CompileExpressionListResult compileExpressionList(List<Expression> expressions) {
      if (expressions.isEmpty()) {
        return new CompileExpressionListResult(
            new int[] { 0 },
            ArraysForStarlark.EMPTY_OBJECT_ARRAY);
      }

      BcWriter.SavedState saved = bcWriter.save();

      int[] opcodeArgs = new int[1 + expressions.size()];
      opcodeArgs[0] = expressions.size();
      Object[] constants = new Object[expressions.size()];
      for (int i = 0; i < expressions.size(); i++) {
        Expression elemExpr = expressions.get(i);
        CompileExpressionResult elem = compileExpression(elemExpr);
        if (constants != null && elem.value != null) {
          constants[i] = elem.value;
        } else {
          constants = null;
        }
        opcodeArgs[i + 1] = elem.slot;
      }
      if (constants != null) {
        saved.reset();
        int objectIndex = bcWriter.allocObject(constants);
        return new CompileExpressionListResult(
            new int[] { BcSlot.objectIndexToNegativeSize(objectIndex) },
            constants);
      }
      return new CompileExpressionListResult(opcodeArgs, null);
    }

    /** Compile expression {@code x + [...]}. */
    @Nullable
    private CompileExpressionResult tryCompilePlusList(
        BinaryOperatorExpression expression, int result) {
      if (expression.getOperator() != TokenKind.PLUS) {
        return null;
      }
      if (!(expression.getY() instanceof ListExpression)
          || ((ListExpression) expression.getY()).isTuple()) {
        return null;
      }

      CompileExpressionResult lhs = compileExpression(expression.getX());

      int[] rhs = compileExpressionList(
          ((ListExpression) expression.getY()).getElements()).opcodeArgs;

      return writeToOut(
          BcInstr.Opcode.PLUS_LIST,
          expression,
          BcWriter.args(lhs.slot, rhs),
          result);
    }

    private CompileExpressionResult compileBinaryOperatorNonShortCicrcuiting(
        BinaryOperatorExpression expression, int result) {
      BcWriter.SavedState saved = bcWriter.save();

      CompileExpressionResult plusList = tryCompilePlusList(expression, result);
      if (plusList != null) {
        return plusList;
      }

      CompileExpressionResult x = compileExpression(expression.getX());
      CompileExpressionResult y = compileExpression(expression.getY());

      if (x.value != null
          && y.value != null
          && Starlark.isImmutable(x.value)
          && Starlark.isImmutable(y.value)) {
        try {
          Object constResult = EvalUtils.binaryOp(
              expression.getOperator(),
              x.value, y.value, thread.getSemantics(), thread.mutability());
          // For example, `[] + []` returns new list
          // so we cannot compile it to constant if result is mutable.
          if (Starlark.isImmutable(constResult)) {
            saved.reset();
            return compileConstantTo(expression, constResult, result);
          }
        } catch (EvalException e) {
          // ignore
        }
      }

      if (x.value instanceof String
          && expression.getOperator() == TokenKind.PERCENT) {
        String format = (String) x.value;
        int percent = BcStrFormat.indexOfSinglePercentS(format);
        // Check that format string has only one `%s` and no other `%`
        if (percent >= 0) {
          saved.reset();
          return compileStringPercent(expression, result, format, percent);
        }
      }

      return writeBinaryOp(expression, expression.getOperator(), x, y, result);
    }

    private CompileExpressionResult writeBinaryOp(Node expression,
        TokenKind operator, CompileExpressionResult x, CompileExpressionResult y, int result) {
      if (result == BcSlot.ANY_FLAG) {
        result = bcWriter.allocSlot();
      }

      switch (operator) {
        case EQUALS_EQUALS:
          write(BcInstr.Opcode.EQ, expression, x.slot, y.slot, result);
          return new CompileExpressionResult(result, null);
        case NOT_EQUALS:
          write(BcInstr.Opcode.NOT_EQ, expression, x.slot, y.slot, result);
          return new CompileExpressionResult(result, null);
        case IN:
          write(BcInstr.Opcode.IN, expression, x.slot, y.slot, result);
          return new CompileExpressionResult(result, null);
        case NOT_IN:
          write(BcInstr.Opcode.NOT_IN, expression, x.slot, y.slot, result);
          return new CompileExpressionResult(result, null);
        case PLUS:
          return writePlus(expression, x, y, result);
        default:
          write(
              BcInstr.Opcode.BINARY,
              expression,
              x.slot,
              y.slot,
              operator.ordinal(),
              result);
          return new CompileExpressionResult(result, null);
      }
    }

    private CompileExpressionResult compileStringPercent(BinaryOperatorExpression expression,
        int result, String format, int percent) {
      // compile again after reset
      CompileExpressionResult y;
      BcInstr.Opcode opcode;
      if (expression.getY() instanceof ListExpression
          && ((ListExpression) expression.getY()).isTuple()
          && ((ListExpression) expression.getY()).getElements().size() == 1) {
        y = compileExpression(((ListExpression) expression.getY()).getElements().get(0));
        opcode = BcInstr.Opcode.PERCENT_S_ONE_TUPLE;
      } else {
        y = compileExpression(expression.getY());
        opcode = BcInstr.Opcode.PERCENT_S_ONE;
      }
      if (result == BcSlot.ANY_FLAG) {
        result = bcWriter.allocSlot();
      }
      write(opcode, expression, bcWriter.allocString(format), percent, y.slot, result);
      return new CompileExpressionResult(result, null);
    }

    private CompileExpressionResult writePlus(Node node,
        CompileExpressionResult x, CompileExpressionResult y, int result) {
      BcInstr.Opcode opcode;
      if (x.value instanceof String || y.value instanceof String) {
        opcode = BcInstr.Opcode.PLUS_STRING;
      } else {
        opcode = BcInstr.Opcode.PLUS;
      }
      write(opcode, node, x.slot, y.slot, result);
      return new CompileExpressionResult(result, null);
    }

    BcCompiled finish() {
      return bcWriter.finish();
    }
  }

  public static BcCompiled compileFunction(StarlarkThread thread, Resolver.Function rfn, Module module,
      Tuple freevars) {
    long start = StarlarkRuntimeStats.ENABLED ? System.nanoTime() : 0;
    Compiler compiler = new Compiler(thread, rfn, module, freevars);
    compiler.compileStatements(rfn.getBody(), rfn.isToplevel());
    BcCompiled compiled = compiler.finish();
    if (StarlarkRuntimeStats.ENABLED) {
      StarlarkRuntimeStats.recordCompileTimeNanos(System.nanoTime() - start);
    }
    return compiled;
  }
}
