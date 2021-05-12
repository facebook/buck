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

    private final FileLocations fileLocations;
    final StarlarkThread thread;
    private final Module module;
    private final Tuple freevars;
    private final ImmutableList<Parameter> parameters;
    private final Resolver.Function rfn;

    private Compiler(StarlarkThread thread, Resolver.Function rfn, Module module, Tuple freevars) {
      Preconditions.checkArgument(rfn.getModule() == module.getResolverModule(),
          "must compile function with the same module used to resolve function,"
              + " otherwise global indices won't match");

      this.rfn = rfn;
      this.fileLocations = rfn.getFileLocations();
      this.thread = thread;
      this.module = module;
      this.freevars = freevars;
      this.parameters = rfn.getParameters();
    }

    BcWriter.LocOffset nodeToLocOffset(Node node) {
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

    private void cp(BcIr ir, Node node, BcIrSlot from, BcIrSlot.AnyLocal to) {
      // Optimizes away assignment `$x = $x`,
      // but keep local `x = x` to make sure we throw an error if `x` is not defined.
      // TODO(nga): drop the assignment if `x` is known to be defined
      if (from instanceof BcIrSlot.Local && to instanceof BcIrSlot.Local) {
        if (((BcIrSlot.Local) from).index == ((BcIrSlot.Local) to).index) {
          // parameters are known to be defined
          if (((BcIrSlot.Local) from).index < parameters.size()) {
            return;
          }
        }
      }
      // Slots are always assigned
      if (from instanceof BcIrSlot.LazyLocal && from == to) {
        return;
      }

      ir.add(new BcIrInstr.Cp(
          nodeToLocOffset(node),
          from,
          to));
    }

    /** Compile. */
    void compileStatements(BcIr ir, List<Statement> statements, boolean postAssignHook) {
      for (Statement statement : statements) {
        compileStatement(ir, statement, postAssignHook);
      }
    }

    private void compileStatement(BcIr ir, Statement statement, boolean postAssignHook) {

      if (statement instanceof ExpressionStatement) {
        // Likely doc comment, skip it
        if (((ExpressionStatement) statement).getExpression() instanceof StringLiteral) {
          return;
        }

        compileExpressionForEffect(ir, ((ExpressionStatement) statement).getExpression());
      } else if (statement instanceof AssignmentStatement) {
        compileAssignment(ir, (AssignmentStatement) statement, postAssignHook);
      } else if (statement instanceof ReturnStatement) {
        compileReturn(ir, (ReturnStatement) statement);
      } else if (statement instanceof IfStatement) {
        compileIfStatement(ir, (IfStatement) statement);
      } else if (statement instanceof ForStatement) {
        compileForStatement(ir, (ForStatement) statement);
      } else if (statement instanceof FlowStatement) {
        compileFlowStatement(ir, (FlowStatement) statement);
      } else if (statement instanceof LoadStatement) {
        compileLoadStatement(ir, (LoadStatement) statement);
      } else if (statement instanceof DefStatement) {
        compileDefStatement(ir, (DefStatement) statement);
      } else {
        throw new RuntimeException("not impl: " + statement.getClass().getSimpleName());
      }
    }

    private void compileReturn(BcIr ir, ReturnStatement statement) {
      BcIrSlot value;
      if (statement.getResult() == null) {
        value = BcIrSlot.Const.NONE;
      } else {
        value = compileExpression(ir, statement.getResult()).slot;
      }
      ir.add(new BcIrInstr.Return(nodeToLocOffset(statement), value));
    }

    private void compileLoadStatement(BcIr ir, LoadStatement loadStatement) {
      ir.add(new BcIrInstr.LoadStmt(nodeToLocOffset(loadStatement), loadStatement));
    }

    private void compileDefStatement(BcIr ir, DefStatement def) {
      BcIrSlot.LazyLocal result = ir.allocSlot("def");
      compileNewFunction(ir, def.getResolvedFunction(), def, result);
      compileSet(ir, result, def.getIdentifier(), true);
    }

    /** Common code to compile def and lambda. */
    private void compileNewFunction(BcIr ir, Resolver.Function rfn, Node node, BcIrSlot.AnyLocal result) {
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

      ImmutableList.Builder<BcIrSlot> defaults = ImmutableList.builder();

      for (int p = 0; p < ndefaults; ++p) {
        Parameter parameter = parameters.get(nparams - ndefaults + p);
        if (parameter.getDefaultValue() != null) {
          defaults.add(compileExpression(ir, parameter.getDefaultValue()).slot);
        } else {
          defaults.add(BcIrSlot.Const.MANDATORY);
        }
      }

      ir.add(new BcIrInstr.NewFunction(
          nodeToLocOffset(node),
          rfn,
          defaults.build(),
          result));
    }

    private void compileIfStatement(BcIr ir, IfStatement ifStatement) {
      new BcCompilerForIf(this).compileIfStatement(ir, ifStatement);
    }

    private void compileFlowStatement(BcIr ir, FlowStatement flowStatement) {
      switch (flowStatement.getKind()) {
        case BREAK:
          compileBreak(ir, flowStatement);
          break;
        case CONTINUE:
          compileContinue(ir, flowStatement);
          break;
        case PASS:
          // nop
          break;
        default:
          throw new IllegalStateException("unknown flow statement: " + flowStatement.getKind());
      }
    }

    private void compileContinue(BcIr ir, Node node) {
      ir.add(new BcIrInstr.Continue(nodeToLocOffset(node)));
    }

    private void compileBreak(BcIr ir, Node node) {
      ir.add(new BcIrInstr.Break(nodeToLocOffset(node)));
    }

    /** Callback invoked to compile the loop body. */
    private interface ForBody {
      void compile(BcIr ir);
    }

    /** Generic compile for loop routine, used in for statement and in loop comprehension. */
    private void compileFor(BcIr ir, Expression vars, Expression collection, ForBody body) {
      CompileExpressionResult iterable = compileExpression(ir, collection);

      // Most common loops are single var loops, we don't need to use temporary variables.
      boolean assignStraightToLocal = vars instanceof Identifier
          && ((Identifier) vars).getBinding().getScope() == Resolver.Scope.LOCAL;

      // Register where we are storing the next iterator value.
      // This register is updated by FOR_INIT and CONTINUE instructions.
      BcIrSlot.AnyLocal nextValueSlot = assignStraightToLocal ?
          localIdentSlot((Identifier) vars) : ir.allocSlot("item");

      ir.add(new BcIrInstr.ForInit(
          nodeToLocOffset(collection),
          iterable.slot,
          nextValueSlot));

      if (!assignStraightToLocal) {
        compileAssignment(ir, nextValueSlot, vars, false);
      }

      body.compile(ir);

      // We use usual CONTINUE statement in the end of the loop.
      // Note: CONTINUE does unnecessary goto e in the end of iteration.
      compileContinue(ir, collection);

      ir.add(BcIrInstr.ForClose.FOR_CLOSE);
    }

    private void compileForStatement(BcIr ir, ForStatement forStatement) {
      compileFor(
          ir,
          forStatement.getVars(),
          forStatement.getCollection(),
          ir1 -> compileStatements(ir1, forStatement.getBody(), false));
    }

    private void compileAssignment(
        BcIr ir, AssignmentStatement assignmentStatement, boolean postAssignHook) {
      if (assignmentStatement.isAugmented()) {
        compileAgumentedAssignment(ir, assignmentStatement);
      } else {
        compileAssignmentRegular(ir, assignmentStatement, postAssignHook);
      }
    }

    private void compileAssignmentRegular(
        BcIr ir, AssignmentStatement assignmentStatement, boolean postAssignHook) {
      Preconditions.checkState(!assignmentStatement.isAugmented());

      Expression lhs = assignmentStatement.getLHS();
      if (lhs instanceof Identifier) {
        Identifier lhsIdent = (Identifier) lhs;
        if (lhsIdent.getBinding().getScope() == Resolver.Scope.LOCAL) {
          compileExpressionTo(ir, assignmentStatement.getRHS(), localIdentSlot(lhsIdent));
          return;
        }
      }

      BcIrSlot rhs = compileExpression(ir, assignmentStatement.getRHS()).slot;
      compileAssignment(ir, rhs, lhs, postAssignHook);
    }

    private void compileAssignment(BcIr ir, BcIrSlot rhs, Expression lhs, boolean postAssignHook) {
      if (lhs instanceof Identifier) {
        compileSet(ir, rhs, (Identifier) lhs, postAssignHook);
      } else if (lhs instanceof ListExpression) {
        compileAssignmentToList(ir, rhs, (ListExpression) lhs, postAssignHook);
      } else if (lhs instanceof IndexExpression) {
        IndexExpression indexExpression = (IndexExpression) lhs;
        BcIrSlot object = compileExpression(ir, indexExpression.getObject()).slot;
        BcIrSlot key = compileExpression(ir, indexExpression.getKey()).slot;
        ir.add(new BcIrInstr.SetIndex(
            nodeToLocOffset(indexExpression),
            object,
            key,
            rhs));
      } else {
        compileThrowException(ir, lhs, String.format("cannot assign to '%s'", lhs));
      }
    }

    private void compileAssignmentToList(BcIr ir, BcIrSlot rhs, ListExpression list, boolean postAssignHook) {
      ArrayList<BcIrSlot.AnyLocal> postUnpackAssignmentSlots = new ArrayList<>();
      ArrayList<Expression> postUnpackAssignmentExprs = new ArrayList<>();

      ImmutableList.Builder<BcIrSlot.AnyLocal> lhs = ImmutableList.builder();
      for (Expression element : list.getElements()) {
        if (element instanceof Identifier
            && ((Identifier) element).getBinding().getScope() == Resolver.Scope.LOCAL) {
          lhs.add(localIdentSlot((Identifier) element));
        } else {
          BcIrSlot.LazyLocal slot = ir.allocSlot("unpack");
          postUnpackAssignmentSlots.add(slot);
          postUnpackAssignmentExprs.add(element);
          lhs.add(slot);
        }
      }

      ir.add(new BcIrInstr.Unpack(
          nodeToLocOffset(list),
          rhs,
          lhs.build()));

      Preconditions.checkState(
          postUnpackAssignmentSlots.size() == postUnpackAssignmentExprs.size());

      for (int j = 0; j < postUnpackAssignmentSlots.size(); ++j) {
        compileAssignment(
            ir,
            postUnpackAssignmentSlots.get(j),
            postUnpackAssignmentExprs.get(j),
            postAssignHook);
      }
    }

    private void compileSet(BcIr ir, BcIrSlot rhs, Identifier identifier, boolean postAssignHook) {
      Resolver.Binding binding = identifier.getBinding();
      switch (binding.getScope()) {
        case LOCAL:
          cp(ir, identifier, rhs, new BcIrSlot.Local(binding.getIndex()));
          break;
        case GLOBAL:
          ir.add(new BcIrInstr.SetGlobal(
              nodeToLocOffset(identifier),
              rhs,
              binding.getIndex(),
              identifier.getName(),
              postAssignHook
          ));
          break;
        case CELL:
          ir.add(new BcIrInstr.SetCell(
              nodeToLocOffset(identifier),
              rhs,
              binding.getIndex()
          ));
          break;
        default:
          throw new IllegalStateException();
      }
    }

    private void compileThrowException(BcIr ir, Node node, String message) {
      ir.add(new BcIrInstr.EvalException(nodeToLocOffset(node), message));
    }

    private void compileAgumentedAssignmentToIdentifier(BcIr ir, AssignmentStatement assignmentStatement) {
      Identifier lhs = (Identifier) assignmentStatement.getLHS();

      AugmentedAssignmentRhs rhs = compileAugmentedAssignmentRhs(
          ir,
          assignmentStatement.getOperator(),
          assignmentStatement.getRHS());

      if (lhs.getBinding().getScope() == Resolver.Scope.LOCAL) {
        writeBinaryInPlace(
            ir,
            assignmentStatement,
            localIdentSlot(lhs),
            rhs,
            localIdentSlot(lhs)
        );
      } else {
        CompileExpressionResult value = compileGet(lhs);
        BcIrSlot.LazyLocal temp = ir.allocSlot("aug");
        writeBinaryInPlace(
            ir,
            assignmentStatement,
            value.slot,
            rhs,
            temp);
        compileSet(ir, temp, lhs, false);
      }
    }

    /** Result of compilation of {@code x (op)= y} rhs. */
    private static class AugmentedAssignmentRhs {
      /** When operator is {@code +=} and RHS is {@code [...]}. */
      @Nullable private final BcIrListArg listResult;
      /** All other values. */
      @Nullable
      private final CompileExpressionResult defaultResult;

      public AugmentedAssignmentRhs(BcIrListArg listResult) {
        this.listResult = listResult;
        this.defaultResult = null;
      }

      public AugmentedAssignmentRhs(CompileExpressionResult defaultResult) {
        this.defaultResult = defaultResult;
        this.listResult = null;
      }
    }

    private AugmentedAssignmentRhs compileAugmentedAssignmentRhs(
        BcIr ir, TokenKind op, Expression rhs) {
      if (op == TokenKind.PLUS
          && rhs instanceof ListExpression
          && !((ListExpression) rhs).isTuple()) {
        return new AugmentedAssignmentRhs(
            compileExpressionList(ir, ((ListExpression) rhs).getElements()));
      } else {
        return new AugmentedAssignmentRhs(compileExpression(ir, rhs));
      }
    }

    private void writeBinaryInPlace(
        BcIr ir,
        AssignmentStatement assignmentStatement,
        BcIrSlot lhs,
        AugmentedAssignmentRhs rhs,
        BcIrSlot.AnyLocal result) {
      if (assignmentStatement.getOperator() == TokenKind.PLUS) {
        // The only operator supporting binary in place is plus for lists
        if (rhs.listResult != null) {
          ir.add(new BcIrInstr.PlusListInPlace(
              nodeToLocOffset(assignmentStatement),
              lhs,
              rhs.listResult,
              result));
        } else {
          Preconditions.checkState(rhs.defaultResult != null);
          BcIrInstr.BinOpOp binOpOp;
          if (rhs.defaultResult.value() instanceof String) {
            binOpOp = BcIrInstr.BinOpOp.PLUS_STRING_IN_PLACE;
          } else {
            binOpOp = BcIrInstr.BinOpOp.PLUS_IN_PLACE;
          }
          ir.add(new BcIrInstr.BinOp(
              nodeToLocOffset(assignmentStatement),
              binOpOp,
              lhs,
              rhs.defaultResult.slot,
              result));
        }
      } else {
        // Otherwise inplace is equivalent to `lhs = lhs + rhs`.
        writeBinaryOp(ir, assignmentStatement,
            assignmentStatement.getOperator(),
            new CompileExpressionResult(lhs), rhs.defaultResult, result);
      }
    }

    private void compileAgumentedAssignment(BcIr ir, AssignmentStatement assignmentStatement) {
      Preconditions.checkState(assignmentStatement.getOperator() != null);
      if (assignmentStatement.getLHS() instanceof Identifier) {
        compileAgumentedAssignmentToIdentifier(ir, assignmentStatement);
      } else if (assignmentStatement.getLHS() instanceof IndexExpression) {
        IndexExpression indexExpression = (IndexExpression) assignmentStatement.getLHS();

        BcIrSlot object = compileExpression(ir, indexExpression.getObject()).slot;
        BcIrSlot key = compileExpression(ir, indexExpression.getKey()).slot;

        object.incRef();
        key.incRef();

        AugmentedAssignmentRhs rhs = compileAugmentedAssignmentRhs(
            ir,
            assignmentStatement.getOperator(),
            assignmentStatement.getRHS());
        BcIrSlot.LazyLocal temp = ir.allocSlot("aug");
        ir.add(new BcIrInstr.Index(
            nodeToLocOffset(assignmentStatement),
            object,
            key,
            temp));
        temp.incRef();
        temp.incRef();
        writeBinaryInPlace(ir, assignmentStatement, temp, rhs, temp);
        ir.add(new BcIrInstr.SetIndex(
            nodeToLocOffset(assignmentStatement),
            object,
            key,
            temp));
      } else if (assignmentStatement.getLHS() instanceof ListExpression) {
        compileThrowException(ir,
            assignmentStatement.getLHS(),
            "cannot perform augmented assignment on a list or tuple expression");
      } else {
        compileThrowException(
            ir,
            assignmentStatement.getLHS(),
            String.format("cannot assign to '%s'", assignmentStatement.getLHS()));
      }
    }

    /** Compile a constant, return a register containing the constant. */
    private CompileExpressionResult compileConstant(Object constant) {
      return new CompileExpressionResult(new BcIrSlot.Const(constant));
    }

    CompileExpressionResult compileConstantTo(BcIr ir, Node node, Object constant, BcIrLocalOrAny result) {
      CompileExpressionResult constResult = compileConstant(constant);
      if (result == BcIrLocalOrAny.Any.ANY) {
        return constResult;
      } else {
        BcIrSlot.AnyLocal local = ((BcIrLocalOrAny.Local) result).local;
        cp(ir, node, constResult.slot, local);
        return new CompileExpressionResult(local);
      }
    }

    private void compileExpressionTo(BcIr ir, Expression expression, BcIrSlot.AnyLocal result) {
      compileExpressionTo(ir, expression, new BcIrLocalOrAny.Local(result));
    }

    /** Compile an expression, store result in provided register. */
    private CompileExpressionResult compileExpressionTo(BcIr ir, Expression expression, BcIrLocalOrAny result) {
      if (expression instanceof SliceExpression) {
        return compileSliceExpression(ir, (SliceExpression) expression, result);
      } else if (expression instanceof Comprehension) {
        return compileComprehension(ir, (Comprehension) expression, result);
      } else if (expression instanceof ListExpression) {
        return compileList(ir, (ListExpression) expression, result);
      } else if (expression instanceof DictExpression) {
        return compileDict(ir, (DictExpression) expression, result);
      } else if (expression instanceof CallExpression) {
        return new BcCompilerForCall(this).compileCall(ir, (CallExpression) expression, result);
      } else if (expression instanceof ConditionalExpression) {
        return compileConditional(ir, (ConditionalExpression) expression, result);
      } else if (expression instanceof DotExpression) {
        return compileDot(ir, (DotExpression) expression, result);
      } else if (expression instanceof IndexExpression) {
        return compileIndex(ir, (IndexExpression) expression, result);
      } else if (expression instanceof UnaryOperatorExpression) {
        return compileUnaryOperator(ir, (UnaryOperatorExpression) expression, result);
      } else if (expression instanceof BinaryOperatorExpression) {
        return compileBinaryOperator(ir, (BinaryOperatorExpression) expression, result);
      } else if (expression instanceof LambdaExpression) {
        return compileLambda(ir, (LambdaExpression) expression, result);
      } else if (expression instanceof Identifier
          || expression instanceof StringLiteral
          || expression instanceof IntLiteral
          || expression instanceof FloatLiteral) {
        CompileExpressionResult exprResult = compileExpression(ir, expression);
        if (result != BcIrLocalOrAny.Any.ANY) {
          BcIrSlot.AnyLocal local = ((BcIrLocalOrAny.Local) result).local;
          cp(ir, expression, exprResult.slot, local);
          return new CompileExpressionResult(local);
        } else {
          return new CompileExpressionResult(exprResult.slot);
        }
      } else {
        throw new RuntimeException("not impl: " + expression.getClass().getSimpleName());
      }
    }

    private CompileExpressionResult compileLambda(BcIr ir,
        LambdaExpression lambda, BcIrLocalOrAny result) {
      BcIrSlot.AnyLocal resultLocal = result.makeLocal(ir, "lambda");
      compileNewFunction(ir, lambda.getResolvedFunction(), lambda, resultLocal);
      return new CompileExpressionResult(resultLocal);
    }

    private CompileExpressionResult compileIntLiteral(IntLiteral intLiteral) {
      StarlarkInt starlarkInt = intLiteralValueAsStarlarkInt(intLiteral);
      return compileConstant(starlarkInt);
    }

    private StarlarkInt intLiteralValueAsStarlarkInt(IntLiteral intLiteral) {
      Number value = intLiteral.getValue();
      if (value instanceof Integer) {
        return StarlarkInt.of((Integer) value);
      } else if (value instanceof Long) {
        return StarlarkInt.of((Long) value);
      } else if (value instanceof BigInteger) {
        return StarlarkInt.of((BigInteger) value);
      } else {
        throw new IllegalStateException();
      }
    }

    static class CompileExpressionResult {
      final BcIrSlot slot;

      CompileExpressionResult(BcIrSlot slot) {
        this.slot = slot;
      }

      @Nullable
      Object value() {
        return slot instanceof BcIrSlot.Const ? ((BcIrSlot.Const) slot).value : null;
      }

      @Nullable
      Object valueImmutable() {
        Object value = value();
        if (value != null && Starlark.isImmutable(value)) {
          return value;
        } else {
          return null;
        }
      }

      @Override
      public String toString() {
        return "CompileExpressionResult{"
            + "slot=" + slot + '}';
      }
    }

    /** Compile expression result with IR to produce that result. */
    static class CompileExpressionResultWithIr {
      final BcIr ir;
      final CompileExpressionResult result;

      public CompileExpressionResultWithIr(BcIr ir, CompileExpressionResult result) {
        if (result.value() != null) {
          Preconditions.checkArgument(ir.size() == 0,
              "if expression produced constant, it should produce no IR");
        }
        this.ir = ir;
        this.result = result;
      }
    }

    /** Compile an expression and return a register containing the result. */
    CompileExpressionResult compileExpression(BcIr ir, Expression expression) {
      if (expression instanceof Identifier) {
        return compileGet((Identifier) expression);
      } else if (expression instanceof StringLiteral) {
        return compileConstant(((StringLiteral) expression).getValue());
      } else if (expression instanceof IntLiteral) {
        return compileIntLiteral((IntLiteral) expression);
      } else if (expression instanceof FloatLiteral) {
        return compileConstant(StarlarkFloat.of(((FloatLiteral) expression).getValue()));
      } else {
        int savedSize = ir.size();
        CompileExpressionResult compileExpressionResult = compileExpressionTo(
            ir, expression, BcIrLocalOrAny.Any.ANY);
        if (compileExpressionResult.value() != null) {
          // If expression evaluated to constant, it should produce no bytecode
          ir.assertUnchanged(savedSize);
        }
        return compileExpressionResult;
      }
    }

    /** Compile expression discarding the result. */
    private void compileExpressionForEffect(BcIr ir, Expression expression) {
      CompileExpressionResult result = compileExpressionTo(ir, expression, BcIrLocalOrAny.Any.ANY);
      result.slot.decRef();
    }

    /** Compile expression and return an IR and result slot. */
    CompileExpressionResultWithIr compileExpression(Expression expression) {
      BcIr ir = new BcIr();
      CompileExpressionResult result = compileExpression(ir, expression);
      return new CompileExpressionResultWithIr(ir, result);
    }

    /** Compile expression or return null slot for null expression. */
    BcIrSlotOrNull compileExpressionOrNull(BcIr ir, @Nullable Expression expression) {
      if (expression != null) {
        return new BcIrSlotOrNull.Slot(compileExpression(ir, expression).slot);
      } else {
        return BcIrSlotOrNull.Null.NULL;
      }
    }

    static class SlotOrNullWithIr {
      final BcIr ir;
      final BcIrSlotOrNull slot;

      SlotOrNullWithIr(BcIr ir, BcIrSlotOrNull slot) {
        if (slot == BcIrSlotOrNull.Null.NULL) {
          Preconditions.checkArgument(ir.size() == 0);
        }
        this.ir = ir;
        this.slot = slot;
      }
    }

    SlotOrNullWithIr compileExpressionOrNull(@Nullable Expression expression) {
      BcIr ir = new BcIr();
      BcIrSlotOrNull slot = compileExpressionOrNull(ir, expression);
      return new SlotOrNullWithIr(ir, slot);
    }

    /**
     * Try compile expression as a constant, return {@code null} if expresssion is not a constant.
     */
    @Nullable
    Object tryCompileConstant(Expression expression) {
      BcIr ir = new BcIr();
      CompileExpressionResult result = compileExpression(ir, expression);
      return result.value();
    }

    private CompileExpressionResult compileIndex(
        BcIr ir, IndexExpression expression, BcIrLocalOrAny result) {
      BcIrSlot.AnyLocal resultLocal = result.makeLocal(ir, "index");

      BcIrSlot object = compileExpression(ir, expression.getObject()).slot;
      BcIrSlot key = compileExpression(ir, expression.getKey()).slot;

      ir.add(new BcIrInstr.Index(
          nodeToLocOffset(expression),
          object,
          key,
          resultLocal));

      return new CompileExpressionResult(resultLocal);
    }

    private CompileExpressionResult compileDot(BcIr ir,
        DotExpression dotExpression, BcIrLocalOrAny result) {
      CompileExpressionResult object = compileExpression(ir, dotExpression.getObject());

      if (object.value() != null) {
        try {
          // This code is correct because for all known objects
          // `getattr` produces the same instance for given `attr`.
          // When it is no longer the case, we can add something like
          // `ImmutableStructure` interface.
          Object attrValue =
              Starlark.getattr(
                  thread,
                  object.value(),
                  dotExpression.getField().getName(),
                  null);
          if (attrValue != null) {
            return compileConstantTo(ir, dotExpression, attrValue, result);
          }
        } catch (EvalException | InterruptedException e) {
          // ignore
        }
      }

      BcIrSlot.AnyLocal resultLocal = result.makeLocal(ir, "dot");

      ir.add(new BcIrInstr.Dot(
          nodeToLocOffset(dotExpression),
          object.slot,
          new BcDotSite(dotExpression.getField().getName()),
          resultLocal));

      return new CompileExpressionResult(resultLocal);
    }

    private CompileExpressionResult compileSliceExpression(BcIr ir,
        SliceExpression slice, BcIrLocalOrAny result) {
      BcIrSlot object = compileExpression(ir, slice.getObject()).slot;

      BcIrSlotOrNull start = compileExpressionOrNull(ir, slice.getStart());
      BcIrSlotOrNull stop = compileExpressionOrNull(ir, slice.getStop());
      BcIrSlotOrNull step = compileExpressionOrNull(ir, slice.getStep());

      BcIrSlot.AnyLocal resultLocal = result.makeLocal(ir, "slice");

      ir.add(new BcIrInstr.Slice(
          nodeToLocOffset(slice),
          object,
          start,
          stop,
          step,
          resultLocal));

      return new CompileExpressionResult(resultLocal);
    }

    private BcIrSlot.Local localIdentSlot(Identifier identifier) {
      Preconditions.checkArgument(identifier.getBinding().getScope() == Resolver.Scope.LOCAL);
      return new BcIrSlot.Local(identifier.getBinding().getIndex());
    }

    private CompileExpressionResult compileGet(Identifier identifier) {
      Resolver.Binding binding = identifier.getBinding();
      if (binding == null) {
        throw new RuntimeException("identifier.binding is null");
      }
      switch (binding.getScope()) {
        case LOCAL:
          return new CompileExpressionResult(localIdentSlot(identifier));
        case GLOBAL:
          int globalVarIndex = binding.getIndex();
          if (!binding.isFirstReassignable()) {
            Object globalValue = module.getGlobalByIndex(globalVarIndex);
            if (globalValue != null) {
              return compileConstant(globalValue);
            }
          }
          return new CompileExpressionResult(new BcIrSlot.Global(globalVarIndex));
        case FREE:
          if (!binding.isFirstReassignable()) {
            StarlarkFunction.Cell cell = (StarlarkFunction.Cell) freevars.get(binding.getIndex());
            if (cell.x != null) {
              return compileConstant(cell.x);
            }
          }
          return new CompileExpressionResult(new BcIrSlot.Free(binding.getIndex()));
        case CELL:
          return new CompileExpressionResult(new BcIrSlot.Cell(binding.getIndex()));
        case UNIVERSAL:
          return compileConstant(Starlark.UNIVERSE_OBJECTS.valueByIndex(binding.getIndex()));
        case PREDECLARED:
          return compileConstant(module.getResolverModule().getPredeclared(binding.getName()));
        default:
          throw new IllegalStateException();
      }
    }

    private CompileExpressionResult compileComprehension(
        BcIr ir, Comprehension comprehension, BcIrLocalOrAny result) {
      // Must explicitly use temporary variable, because comprehension expression
      // may reference to the same slot we are about to write.
      BcIrSlot.LazyLocal temp = ir.allocSlot("compr");
      if (comprehension.isDict()) {
        ir.add(new BcIrInstr.Dict(
            nodeToLocOffset(comprehension),
            ImmutableList.of(),
            temp));
      } else {
        ir.add(new BcIrInstr.List(
            nodeToLocOffset(comprehension),
            BcIrListArg.Slots.EMPTY,
            temp));
      }

      // The Lambda class serves as a recursive lambda closure.
      class Lambda {
        // execClauses(index) recursively compiles the clauses starting at index,
        // and finally compiles the body and adds its value to the result.
        private void compileClauses(BcIr ir, int index) {
          // recursive case: one or more clauses
          if (index != comprehension.getClauses().size()) {
            Comprehension.Clause clause = comprehension.getClauses().get(index);
            if (clause instanceof Comprehension.For) {
              compileFor(
                  ir,
                  ((Comprehension.For) clause).getVars(),
                  ((Comprehension.For) clause).getIterable(),
                  ir1 -> compileClauses(ir1, index + 1));
            } else if (clause instanceof Comprehension.If) {
              CompileExpressionResult cond = compileExpression(ir, ((Comprehension.If) clause).getCondition());
              // TODO: optimize if cond != null
              BcIrInstr.JumpLabel end = ir.ifBr(nodeToLocOffset(clause), cond.slot, BcWriter.JumpCond.IF_NOT);
              compileClauses(ir, index + 1);
              ir.add(end);
            } else {
              throw new IllegalStateException("unknown compr clause: " + clause);
            }
          } else {
            temp.incRef();
            if (comprehension.isDict()) {
              DictExpression.Entry entry = (DictExpression.Entry) comprehension.getBody();
              BcIrSlot key = compileExpression(ir, entry.getKey()).slot;
              BcIrSlot value = compileExpression(ir, entry.getValue()).slot;
              ir.add(new BcIrInstr.SetIndex(
                  nodeToLocOffset(entry),
                  temp,
                  key,
                  value));
            } else {
              BcIrSlot value = compileExpression(ir, (Expression) comprehension.getBody()).slot;
              ir.add(new BcIrInstr.ListAppend(
                  nodeToLocOffset(comprehension),
                  temp,
                  value));
            }
          }
        }
      }

      new Lambda().compileClauses(ir, 0);
      if (result == BcIrLocalOrAny.Any.ANY) {
        return new CompileExpressionResult(temp);
      } else {
        BcIrSlot.AnyLocal resultLocal = result.makeLocal(ir, "compr_r");
        cp(ir, comprehension, temp, resultLocal);
        return new CompileExpressionResult(resultLocal);
      }
    }

    private CompileExpressionResult compileDict(BcIr ir,
        DictExpression dictExpression, BcIrLocalOrAny result) {
      BcIrSlot.AnyLocal resultLocal = result.makeLocal(ir, "dict");

      ImmutableList.Builder<BcIrSlot> args = ImmutableList
          .builderWithExpectedSize(dictExpression.getEntries().size() * 2);
      for (DictExpression.Entry entry : dictExpression.getEntries()) {
        args.add(compileExpression(ir, entry.getKey()).slot);
        args.add(compileExpression(ir, entry.getValue()).slot);
      }

      ir.add(new BcIrInstr.Dict(
          nodeToLocOffset(dictExpression),
          args.build(),
          resultLocal));

      return new CompileExpressionResult(resultLocal);
    }

    private CompileExpressionResult compileList(BcIr ir,
        ListExpression listExpression, BcIrLocalOrAny result) {

      int savedSize = ir.size();

      BcIrListArg elements = compileExpressionList(
          ir, listExpression.getElements());

      if (elements.data() != null) {
        if (listExpression.isTuple()) {
          ir.assertUnchanged(savedSize);
          return compileConstantTo(ir, listExpression, Tuple.wrap(elements.data()), result);
        }
      }

      BcIrSlot.AnyLocal resultLocal = result.makeLocal(ir, "list");

      if (listExpression.isTuple()) {
        ir.add(new BcIrInstr.Tuple(
            nodeToLocOffset(listExpression),
            elements,
            resultLocal));
      } else {
        ir.add(new BcIrInstr.List(
            nodeToLocOffset(listExpression),
            elements,
            resultLocal));
      }

      return new CompileExpressionResult(resultLocal);
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

    private CompileExpressionResult compileConditional(BcIr ir,
        ConditionalExpression conditionalExpression, BcIrLocalOrAny result) {
      int savedSize = ir.size();

      CompileExpressionResult cond = compileExpression(
          ir, conditionalExpression.getCondition());
      if (cond.value() != null && isTruthImmutable(cond.value())) {
        ir.assertUnchanged(savedSize);
        if (Starlark.truth(cond.value())) {
          return compileExpressionTo(ir, conditionalExpression.getThenCase(), result);
        } else {
          return compileExpressionTo(ir, conditionalExpression.getElseCase(), result);
        }
      }

      BcIrSlot.AnyLocal resultLocal = result.makeLocal(ir, "cond");
      resultLocal.incRef();

      BcIrInstr.JumpLabel thenTarget = ir.ifBr(
          nodeToLocOffset(conditionalExpression), cond.slot, BcWriter.JumpCond.IF_NOT);
      compileExpressionTo(ir, conditionalExpression.getThenCase(), resultLocal);
      BcIrInstr.JumpLabel endTarget = ir.br(nodeToLocOffset(conditionalExpression));
      ir.add(thenTarget);
      compileExpressionTo(ir, conditionalExpression.getElseCase(), resultLocal);
      ir.add(endTarget);

      return new CompileExpressionResult(resultLocal);
    }

    private CompileExpressionResult compileUnaryOperator(BcIr ir,
        UnaryOperatorExpression expression, BcIrLocalOrAny result) {
      int savedSize = ir.size();

      CompileExpressionResult value = compileExpression(ir, expression.getX());

      if (expression.getOperator() == TokenKind.NOT && value.value() != null && isTruthImmutable(value.value())) {
        ir.assertUnchanged(savedSize);
        return compileConstantTo(ir, expression, !Starlark.truth(value.value()), result);
      }

      BcIrSlot.AnyLocal resultLocal = result.makeLocal(ir, "un");

      BcIrInstr.UnOpOp unOpOp = BcIrInstr.UnOpOp.fromToken(expression.getOperator());

      BcIrInstr.UnOp unOp = new BcIrInstr.UnOp(
          nodeToLocOffset(expression), unOpOp, value.slot, resultLocal);

      ir.add(unOp);

      return new CompileExpressionResult(resultLocal);
    }

    private CompileExpressionResult compileBinaryOperator(BcIr ir,
        BinaryOperatorExpression expression, BcIrLocalOrAny result) {
      switch (expression.getOperator()) {
        case AND:
        case OR:
          return compileAndOr(ir, expression, result);
        default:
          return compileBinaryOperatorNonShortCicrcuiting(
              ir, expression, result);
      }
    }

    private CompileExpressionResult compileAndOr(
        BcIr ir, BinaryOperatorExpression expression, BcIrLocalOrAny result) {
      BcWriter.JumpCond jumpCond;
      switch (expression.getOperator()) {
        case AND:
          jumpCond = BcWriter.JumpCond.IF_NOT;
          break;
        case OR:
          jumpCond = BcWriter.JumpCond.IF;
          break;
        default:
          throw new IllegalArgumentException();
      }

      int savedSize = ir.size();
      CompileExpressionResult lhs = compileExpression(ir, expression.getX());
      if (lhs.value() != null && isTruthImmutable(lhs.value())) {
        ir.assertUnchanged(savedSize);
        if (Starlark.truth(lhs.value()) != (expression.getOperator() == TokenKind.AND)) {
          return compileConstantTo(ir, expression, lhs.value(), result);
        } else {
          return compileExpressionTo(ir, expression.getY(), result);
        }
      }

      BcIrSlot.AnyLocal resultLocal = result.makeLocal(ir, "and_or");

      lhs.slot.incRef();

      BcIrInstr.JumpLabel elseTarget = ir.ifBr(
          nodeToLocOffset(expression),
          lhs.slot,
          jumpCond);
      compileExpressionTo(ir, expression.getY(), resultLocal);
      BcIrInstr.JumpLabel endTarget = ir.br(nodeToLocOffset(expression));
      ir.add(elseTarget);
      resultLocal.incRef();
      cp(ir, expression, lhs.slot, resultLocal);
      ir.add(endTarget);
      return new CompileExpressionResult(resultLocal);
    }

    BcIrListArg compileExpressionList(BcIr ir, List<Expression> expressions) {
      if (expressions.isEmpty()) {
        return BcIrListArg.Slots.EMPTY;
      }

      int initialSize = ir.size();

      ImmutableList.Builder<BcIrSlot> slots = ImmutableList
          .builderWithExpectedSize(expressions.size());

      Object[] constants = new Object[expressions.size()];
      for (int i = 0; i < expressions.size(); i++) {
        Expression elemExpr = expressions.get(i);
        CompileExpressionResult elem = compileExpression(ir, elemExpr);
        if (constants != null && elem.value() != null) {
          constants[i] = elem.value();
        } else {
          constants = null;
        }
        slots.add(elem.slot);
      }
      if (constants != null) {
        Preconditions.checkState(ir.size() == initialSize);
        return new BcIrListArg.ListData(constants);
      }
      return new BcIrListArg.Slots(slots.build());
    }

    static class ListArgWithIr {
      final BcIr ir;
      final BcIrListArg listArg;

      public ListArgWithIr(BcIr ir, BcIrListArg listArg) {
        if (listArg.data() != null) {
          Preconditions.checkArgument(
              ir.size() == 0, "must produce no bytecode for constants");
        }
        this.listArg = listArg;
        this.ir = ir;
      }
    }

    ListArgWithIr compileExpressionList(List<Expression> expressions) {
      BcIr ir = new BcIr();
      BcIrListArg listArg = compileExpressionList(ir, expressions);
      return new ListArgWithIr(ir, listArg);
    }

    /** Compile expression {@code x + [...]}. */
    @Nullable
    private CompileExpressionResult tryCompilePlusList(
        BcIr ir, BinaryOperatorExpression expression, BcIrLocalOrAny result) {
      if (expression.getOperator() != TokenKind.PLUS) {
        return null;
      }
      if (!(expression.getY() instanceof ListExpression)
          || ((ListExpression) expression.getY()).isTuple()) {
        return null;
      }

      CompileExpressionResult lhs = compileExpression(ir, expression.getX());

      BcIrListArg rhs = compileExpressionList(
          ir, ((ListExpression) expression.getY()).getElements());

      BcIrSlot.AnyLocal resultLocal = result.makeLocal(ir, "plus_list");

      ir.add(new BcIrInstr.PlusList(
          nodeToLocOffset(expression),
          lhs.slot,
          rhs,
          resultLocal));

      return new CompileExpressionResult(resultLocal);
    }

    CompileExpressionResult writeTypeIs(
        BcIr ir, Node expression, BcIrSlot slot, String type, BcIrLocalOrAny result) {
      BcIrSlot.AnyLocal local = ir.makeLocal(nodeToLocOffset(expression), slot, "type_is_v");

      BcIrSlot.AnyLocal resultLocal = result.makeLocal(ir, "type_is");

      ir.add(new BcIrInstr.TypeIs(
          nodeToLocOffset(expression),
          local,
          type,
          resultLocal));

      return new CompileExpressionResult(resultLocal);
    }

    /** Compile expression to {@link BcInstr.Opcode#TYPE_IS}. */
    @Nullable
    private CompileExpressionResult tryCompileTypeIs(
        BcIr ir,
        BinaryOperatorExpression expression,
        CompileExpressionResult x,
        CompileExpressionResult y,
        BcIrLocalOrAny result) {
      if (expression.getOperator() != TokenKind.EQUALS_EQUALS) {
        return null;
      }

      // Try compile `type(x) == y`
      CompileExpressionResult callConst = tryCompileTypeIsCallConst(
          ir, expression, expression.getX(), x, y, result);
      if (callConst != null) {
        return callConst;
      }

      // Otherwise try compile `x == type(y)`
      return tryCompileTypeIsCallConst(
          ir, expression, expression.getY(), y, x, result);
    }

    @Nullable
    private CompileExpressionResult tryCompileTypeIsCallConst(
        BcIr ir,
        Node expression,
        Expression maybeTypeCallExpression,
        CompileExpressionResult x, CompileExpressionResult y, BcIrLocalOrAny result) {
      if (x.value() != null) {
        return null;
      }

      if (!(y.value() instanceof String)) {
        return null;
      }

      if (!(maybeTypeCallExpression instanceof CallExpression)) {
        return null;
      }

      CallExpression callExpression = (CallExpression) maybeTypeCallExpression;
      Object fn = tryCompileConstant(callExpression.getFunction());
      if (fn != BcDesc.TYPE) {
        return null;
      }

      if (callExpression.getArguments().size() != 1
          || !(callExpression.getArguments().get(0) instanceof Argument.Positional)) {
        // incorrect `type()` call, let it fail at runtime
        return null;
      }

      // Now we know the call is `type(x) == y`

      BcIrSlot typeArgument = compileExpression(ir, callExpression.getArguments().get(0).getValue()).slot;

      return writeTypeIs(ir, expression, typeArgument, (String) y.value(), result);
    }

    private CompileExpressionResult compileBinaryOperatorNonShortCicrcuiting(
        BcIr ir, BinaryOperatorExpression expression, BcIrLocalOrAny result) {
      int savedSize = ir.size();

      CompileExpressionResult plusList = tryCompilePlusList(ir, expression, result);
      if (plusList != null) {
        return plusList;
      }

      BcIr localIr = new BcIr();
      CompileExpressionResult x = compileExpression(localIr, expression.getX());
      CompileExpressionResult y = compileExpression(localIr, expression.getY());

      if (x.valueImmutable() != null
          && y.valueImmutable() != null) {
        try {
          Object constResult = EvalUtils.binaryOp(
              expression.getOperator(),
              x.valueImmutable(), y.valueImmutable(), thread.getSemantics(), thread.mutability());
          // For example, `[] + []` returns new list
          // so we cannot compile it to constant if result is mutable.
          if (Starlark.isImmutable(constResult)) {
            ir.assertUnchanged(savedSize);
            return compileConstantTo(ir, expression, constResult, result);
          }
        } catch (EvalException e) {
          // ignore
        }
      }

      if (x.value() instanceof String
          && expression.getOperator() == TokenKind.PERCENT) {
        String format = (String) x.value();
        int percent = BcStrFormat.indexOfSinglePercentS(format);
        // Check that format string has only one `%s` and no other `%`
        if (percent >= 0) {
          // discard local IR because we are going to compile RHS again
          return compileStringPercent(ir, expression, format, percent, result);
        }
      }

      CompileExpressionResult typeIsResult = tryCompileTypeIs(ir, expression, x, y, result);
      if (typeIsResult != null) {
        return typeIsResult;
      }

      ir.addAll(localIr);

      BcIrSlot.AnyLocal resultLocal = result.makeLocal(ir, "bin");

      writeBinaryOp(ir, expression, expression.getOperator(), x, y, resultLocal);
      return new CompileExpressionResult(resultLocal);
    }

    private void writeBinaryOp(BcIr ir, Node expression,
        TokenKind operator, CompileExpressionResult x, CompileExpressionResult y, BcIrSlot.AnyLocal result) {
      switch (operator) {
        case PLUS:
          writePlus(ir, expression, x, y, result);
          break;
        default:
          BcIrInstr.BinOpOp binOpOp = BcIrInstr.BinOpOp.fromToken(operator);
          ir.add(new BcIrInstr.BinOp(
              nodeToLocOffset(expression),
              binOpOp,
              x.slot,
              y.slot,
              result));
      }
    }

    private CompileExpressionResult compileStringPercent(
        BcIr ir,
        BinaryOperatorExpression expression, String format, int percent, BcIrLocalOrAny result) {
      // compile again after reset
      CompileExpressionResult y;
      boolean tuple;
      if (expression.getY() instanceof ListExpression
          && ((ListExpression) expression.getY()).isTuple()
          && ((ListExpression) expression.getY()).getElements().size() == 1) {
        y = compileExpression(ir, ((ListExpression) expression.getY()).getElements().get(0));
        tuple = true;
      } else {
        y = compileExpression(ir, expression.getY());
        tuple = false;
      }
      BcIrSlot.AnyLocal resultLocal = result.makeLocal(ir, "str_percent");
      ir.add(new BcIrInstr.PercentSOne(
          nodeToLocOffset(expression),
          format,
          percent,
          y.slot,
          tuple,
          resultLocal));
      return new CompileExpressionResult(resultLocal);
    }

    private void writePlus(BcIr ir, Node node,
        CompileExpressionResult x, CompileExpressionResult y, BcIrSlot.AnyLocal result) {

      BcIrInstr.BinOpOp binOpOp;
      if (x.value() instanceof String || y.value() instanceof String) {
        binOpOp = BcIrInstr.BinOpOp.PLUS_STRING;
      } else {
        binOpOp = BcIrInstr.BinOpOp.PLUS;
      }
      ir.add(new BcIrInstr.BinOp(
          nodeToLocOffset(node),
          binOpOp,
          x.slot,
          y.slot,
          result));
    }

    private BcCompiled compile() {
      BcIr ir = new BcIr();
      compileStatements(ir, rfn.getBody(), rfn.isToplevel());

      Object returnsConst = ir.returnsConst();
      String returnsTypeIs = ir.returnsTypeIsOfParam0();

      BcWriter bcWriter = new BcWriter(
          rfn.getFileLocations(),
          module,
          rfn.getName(),
          rfn.getLocals(),
          rfn.getFreeVars());
      ir.write(bcWriter);
      return bcWriter.finish(returnsConst, returnsTypeIs);
    }
  }

  public static BcCompiled compileFunction(
      StarlarkThread thread, Resolver.Function rfn, Module module, Tuple freevars) {
    if (StarlarkRuntimeStats.ENABLED) {
      StarlarkRuntimeStats.enter(StarlarkRuntimeStats.WhereWeAre.BC_COMPILE);
    }
    try {
      Compiler compiler = new Compiler(thread, rfn, module, freevars);
      return compiler.compile();
    } finally {
      if (StarlarkRuntimeStats.ENABLED) {
        StarlarkRuntimeStats.leave();
      }
    }
  }
}
