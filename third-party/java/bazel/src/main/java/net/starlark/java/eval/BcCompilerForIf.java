package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import java.util.ArrayList;
import javax.annotation.Nullable;
import net.starlark.java.syntax.BinaryOperatorExpression;
import net.starlark.java.syntax.Expression;
import net.starlark.java.syntax.IfStatement;
import net.starlark.java.syntax.TokenKind;
import net.starlark.java.syntax.UnaryOperatorExpression;

/** Compile {@code if} statement. */
class BcCompilerForIf {

  private final BcCompiler compiler;

  BcCompilerForIf(BcCompiler compiler) {
    this.compiler = compiler;
  }

  /** Compile expression just for side effects. */
  private void compileForEffect(BcIr ir, BoolExpr cond) {
    Preconditions.checkState(
        cond.maybeConst != null, "Can only compile const expressions: %s", cond);
    cond.match(
        new BoolExprMatcher() {
          @Override
          public void constExpr(ConstExpr constExpr) {
            // no-op
          }

          @Override
          public void other(OtherExpr otherExpr) {
            throw new AssertionError("expression is not const: " + otherExpr);
          }

          @Override
          public void binOp(BinOpExpr binOpExpr) {
            Boolean lhsConst = binOpExpr.lhs.maybeConst;
            if (lhsConst != null) {
              compileForEffect(ir, binOpExpr.lhs);
              // `lhs` is const. So we need to compile `rhs` for effect only if either:
              // * `(lhs === True) AND ...`
              // * `(lhs === False) OR ...`
              if (lhsConst == (binOpExpr.binOp == BinOp.AND)) {
                compileForEffect(ir, binOpExpr.rhs);
              }
            } else {
              // Compile AND expression like:
              // ```
              // if lhs:
              //   rhs
              // ```
              // and OR expression like:
              // ```
              // if not lhs:
              //   rhs
              // ```
              ArrayList<BcIrInstr.JumpLabel> thenAddrs = new ArrayList<>();
              ArrayList<BcIrInstr.JumpLabel> elseAddrs = new ArrayList<>();
              compileCond(ir, binOpExpr.lhs, binOpExpr.binOp != BinOp.AND, elseAddrs, thenAddrs);
              ir.addJumpLabels(thenAddrs);
              compileForEffect(ir, binOpExpr.rhs);
              ir.addJumpLabels(elseAddrs);
            }
          }

          @Override
          public void not(NotExpr notExpr) {
            compileForEffect(ir, notExpr.arg);
          }
        });
  }

  /** Compile if statement condition. */
  // The parameters are these:
  // * `cond` is a condition we are compiling
  // * `elseJumpCond` is a condition on which we should jump
  // * `elseJumps` is where we should jump if condition is satisfied
  // * `thenJumps` is where we _can_ jump if condition is not satisfied
  //     but if condition is not satisfied, we are allowed to just continue execution.
  //
  // Consider compilation of this expression:
  // ```
  // if (x or y) and z: ...
  // ```
  // With all the trickery, we can compile condition with no temporary variables
  // and zero `BR` instructions:
  // ```
  // IF_BR_LOCAL x @z
  // IF_NOT_BR_LOCAL y @else
  // @z:
  // IF_NOT_BR_LOCAL z @else
  // <THEN>
  // BR @end
  // @else:
  // <ELSE>
  // @end:
  // ```
  // Local variables are never used, but `BR` instructions are rarely used
  // for certain complicated cases when evaluating conditions with side effects.
  private void compileCond(
      BcIr ir,
      BoolExpr cond,
      boolean elseJumpCond,
      ArrayList<BcIrInstr.JumpLabel> elseJumps,
      ArrayList<BcIrInstr.JumpLabel> thenJumps) {
    Preconditions.checkState(
        cond.maybeConst == null, "Can only compile non-const expressions: %s", cond);
    cond.match(
        new BoolExprMatcher() {
          @Override
          public void not(NotExpr notExpr) {
            compileCond(ir, ((NotExpr) cond).arg, !elseJumpCond, elseJumps, thenJumps);
          }

          @Override
          public void binOp(BinOpExpr binOpExpr) {
            Boolean xConst = binOpExpr.lhs.maybeConst;
            Boolean yConst = binOpExpr.rhs.maybeConst;
            Verify.verify(xConst == null || yConst == null);

            // Avoid unnecessary jumps in generated code when `binOpExpr`
            // is equivalent to `lhs`.
            if (xConst != null && !binOpExpr.lhs.hasEffects) {
              compileCond(ir, binOpExpr.rhs, elseJumpCond, elseJumps, thenJumps);
              return;
            }
            if (yConst != null && !binOpExpr.rhs.hasEffects) {
              compileCond(ir, binOpExpr.lhs, elseJumpCond, elseJumps, thenJumps);
              return;
            }

            Verify.verify(binOpExpr.lhs.hasEffects);
            Verify.verify(binOpExpr.rhs.hasEffects);

            BinOp binOp = binOpExpr.binOp;
            if ((binOp == BinOp.AND) != elseJumpCond) {

              // This branch handles either of expressions:
              // expression    | elseJumpCond
              // --------------+--------
              // x and y       | false
              // not (x or y)  | true

              if (xConst != null) {
                Verify.verify(xConst != elseJumpCond);
                compileForEffect(ir, binOpExpr.lhs);
              } else {
                ArrayList<BcIrInstr.JumpLabel> xSkip = new ArrayList<>();
                compileCond(ir, binOpExpr.lhs, elseJumpCond, elseJumps, xSkip);
                ir.addJumpLabels(xSkip);
              }

              if (yConst != null) {
                Verify.verify(yConst != elseJumpCond);
                compileForEffect(ir, binOpExpr.rhs);
              } else {
                compileCond(ir, binOpExpr.rhs, elseJumpCond, elseJumps, thenJumps);
              }
            } else {

              // This branch handles either of expressions:
              // expression    | elseJumpCond
              // --------------+--------
              // x or y        | false
              // not (x and y) | true

              if (xConst != null) {
                Verify.verify(xConst == elseJumpCond);
                compileForEffect(ir, binOpExpr.lhs);
              } else {
                ArrayList<BcIrInstr.JumpLabel> xSkip = new ArrayList<>();
                compileCond(ir, binOpExpr.lhs, !elseJumpCond, thenJumps, xSkip);
                ir.addJumpLabels(xSkip);
              }

              if (yConst != null) {
                Verify.verify(yConst == elseJumpCond);
                compileForEffect(ir, binOpExpr.rhs);
                // This is a tricky part: we compile expression like:
                // ```
                // if x or y: ... else: ...
                // ```
                // Where:
                // * `x` is not const
                // * `x` was evaluated to `true`
                // * `y` is const false
                // * `y` has side effects
                // Thus we need to evaluate `y` for side effects,
                // but also unconditionally jump to else.
                elseJumps.add(ir.br(compiler.nodeToLocOffset(binOpExpr.expr)));
              } else {
                compileCond(ir, binOpExpr.rhs, elseJumpCond, elseJumps, thenJumps);
              }
            }
          }

          @Override
          public void constExpr(ConstExpr constExpr) {
            throw new AssertionError("expression is const: " + constExpr);
          }

          @Override
          public void other(OtherExpr otherExpr) {
            writeOtherCond(ir, otherExpr, elseJumpCond, elseJumps);
          }
        });
  }

  /** Write condition which is not logical expression. */
  private void writeOtherCond(
      BcIr ir,
      OtherExpr otherExpr,
      boolean elseJumpCond,
      ArrayList<BcIrInstr.JumpLabel> elseJumps) {
    // Try compile condition as type is br
    BcIr.PopTypeIs popTypeIs = otherExpr.result.ir.popTypeIs(otherExpr.result.result.slot);
    if (popTypeIs != null) {
      writeOtherCondTypeIs(ir, otherExpr, popTypeIs, elseJumpCond, elseJumps);
      return;
    }

    // Try compile condition as bin op br
    if (otherExpr.expr instanceof BinaryOperatorExpression) {
      BinaryOperatorExpression binaryOperatorExpression = (BinaryOperatorExpression) otherExpr.expr;
      BcWriter.JumpBindCond jumpBindCond =
          BcWriter.JumpBindCond.fromBinOpToken(binaryOperatorExpression.getOperator());
      if (jumpBindCond != null) {
        writeOtherCondBinOp(ir, binaryOperatorExpression, elseJumpCond, jumpBindCond, elseJumps);
        return;
      }
    }

    // Write default conditional jump
    writeOtherCondDefault(ir, otherExpr, elseJumpCond, elseJumps);
  }

  private void writeOtherCondTypeIs(
      BcIr ir,
      OtherExpr otherExpr,
      BcIr.PopTypeIs popTypeIs,
      boolean elseJumpCond,
      ArrayList<BcIrInstr.JumpLabel> elseJumps) {
    ir.addAll(otherExpr.result.ir);
    BcWriter.JumpCond jumpCond = elseJumpCond ? BcWriter.JumpCond.IF : BcWriter.JumpCond.IF_NOT;
    BcIrIfCond.TypeIs ifCond = new BcIrIfCond.TypeIs(popTypeIs.value, popTypeIs.type, jumpCond);
    BcIrInstr.JumpLabel jumpLabel = ir.ifBr(compiler.nodeToLocOffset(otherExpr.expr), ifCond);
    elseJumps.add(jumpLabel);
  }

  private void writeOtherCondBinOp(
      BcIr ir,
      BinaryOperatorExpression binaryOperatorExpression,
      boolean elseJumpCond,
      BcWriter.JumpBindCond cond,
      ArrayList<BcIrInstr.JumpLabel> elseJumps) {
    BcIrSlot x = compiler.compileExpression(ir, binaryOperatorExpression.getX()).slot;
    BcIrSlot y = compiler.compileExpression(ir, binaryOperatorExpression.getY()).slot;
    BcWriter.JumpBindCond jumpBindCond = elseJumpCond ? cond : cond.not();
    BcIrIfCond.Bin ifCond = new BcIrIfCond.Bin(x, y, jumpBindCond);
    BcIrInstr.JumpLabel jumpLabel =
        ir.ifBr(compiler.nodeToLocOffset(binaryOperatorExpression), ifCond);
    elseJumps.add(jumpLabel);
  }

  private void writeOtherCondDefault(
      BcIr ir,
      OtherExpr otherExpr,
      boolean elseJumpCond,
      ArrayList<BcIrInstr.JumpLabel> elseJumps) {
    ir.addAll(otherExpr.result.ir);
    BcWriter.LocOffset locOffset = compiler.nodeToLocOffset(otherExpr.expr);
    BcWriter.JumpCond jumpCond = elseJumpCond ? BcWriter.JumpCond.IF : BcWriter.JumpCond.IF_NOT;
    BcIrInstr.JumpLabel jumpLabel = ir.ifBr(locOffset, otherExpr.result.result.slot, jumpCond);
    elseJumps.add(jumpLabel);
  }

  BcCompiler.StmtFlow compileIfStatement(BcIr ir, IfStatement ifStatement) {
    return compileIfElse(
        ir,
        ifStatement.getCondition(),
        ir1 -> compiler.compileStatements(ir1, ifStatement.getThenBlock(), false),
        ifStatement.getElseBlock() != null
            ? ir1 -> compiler.compileStatements(ir1, ifStatement.getElseBlock(), false)
            : null);
  }

  interface Block {
    BcCompiler.StmtFlow compileBlock(BcIr ir);
  }

  void compileIf(BcIr ir, Expression condExpr, Block thenBlock) {
    compileIfElse(ir, condExpr, thenBlock, null);
  }

  BcCompiler.StmtFlow compileIfElse(
      BcIr ir, Expression condExpr, Block thenBlock, @Nullable Block elseBlock) {
    BoolExpr cond = convert(condExpr);

    Boolean condConst = cond.maybeConst;
    if (condConst != null) {
      compileForEffect(ir, cond);
      if (condConst) {
        return thenBlock.compileBlock(ir);
      } else {
        if (elseBlock != null) {
          return elseBlock.compileBlock(ir);
        } else {
          return BcCompiler.StmtFlow.GO_ON;
        }
      }
    }

    ArrayList<BcIrInstr.JumpLabel> elseAddrs = new ArrayList<>();
    ArrayList<BcIrInstr.JumpLabel> thenAddrs = new ArrayList<>();
    // If cond == false, jump to elseAddr, otherwise jump to then addr or just fall through.
    compileCond(ir, cond, false, elseAddrs, thenAddrs);

    ir.addJumpLabels(thenAddrs);
    BcCompiler.StmtFlow thenFlow = thenBlock.compileBlock(ir);
    if (elseBlock != null) {
      // TODO(nga): no need to jump if the last instruction is return
      BcIrInstr.JumpLabel end = ir.br(compiler.nodeToLocOffset(condExpr));
      ir.addJumpLabels(elseAddrs);
      BcCompiler.StmtFlow elseFlow = elseBlock.compileBlock(ir);
      ir.add(end);
      return thenFlow == elseFlow ? thenFlow : BcCompiler.StmtFlow.GO_ON;
    } else {
      ir.addJumpLabels(elseAddrs);
      return BcCompiler.StmtFlow.GO_ON;
    }
  }

  /** Visitor. */
  private abstract static class BoolExprMatcher {
    public abstract void constExpr(ConstExpr constExpr);

    public abstract void other(OtherExpr otherExpr);

    public abstract void binOp(BinOpExpr binOpExpr);

    public abstract void not(NotExpr notExpr);
  }

  private abstract static class BoolExpr {
    /** AST expression for this expression. */
    final Expression expr;
    /**
     * Whether this expression evaluates to constant (yes, no, unknown).
     *
     * <p>Note const expression may still have side effects which need to be evaluated. For example,
     * this expression: {@code True or print(1)} is const {@code true}, but still has side effects.
     */
    @Nullable final Boolean maybeConst;
    /** Evaluation of this code has side effects. */
    final boolean hasEffects;

    protected BoolExpr(Expression expr, @Nullable Boolean maybeConst, boolean hasEffects) {
      this.expr = expr;
      this.maybeConst = maybeConst;
      this.hasEffects = hasEffects;
    }

    abstract void match(BoolExprMatcher matcher);

    @Override
    public abstract String toString();
  }

  /** {@code True} or {@code False}. */
  private static class ConstExpr extends BoolExpr {
    private final boolean value;

    private ConstExpr(Expression expr, boolean value) {
      super(expr, value, /* hasEffects */ false);
      this.value = value;
    }

    @Override
    void match(BoolExprMatcher matcher) {
      matcher.constExpr(this);
    }

    @Override
    public String toString() {
      return value ? "True" : "False";
    }
  }

  /** Any other expression we know nothing about. */
  private static class OtherExpr extends BoolExpr {
    private final BcCompiler.CompileExpressionResultWithIr result;

    private OtherExpr(Expression expr, BcCompiler.CompileExpressionResultWithIr result) {
      super(expr, /* maybeConst */ null, /* hasEffects */ true);
      this.result = result;
    }

    @Override
    void match(BoolExprMatcher matcher) {
      matcher.other(this);
    }

    @Override
    public String toString() {
      return expr.toString();
    }
  }

  private enum BinOp {
    AND(TokenKind.AND),
    OR(TokenKind.OR),
    ;

    private final TokenKind tokenKind;

    BinOp(TokenKind tokenKind) {
      this.tokenKind = tokenKind;
    }

    @Override
    public String toString() {
      return tokenKind.toString();
    }
  }

  /** Logical binary operator expression. */
  private static class BinOpExpr extends BoolExpr {
    private final BoolExpr lhs;
    private final BoolExpr rhs;
    private final BinOp binOp;

    BinOpExpr(Expression expr, BoolExpr lhs, BoolExpr rhs, BinOp binOp) {
      super(expr, computeMaybeConst(lhs, rhs, binOp), lhs.hasEffects || rhs.hasEffects);
      this.lhs = lhs;
      this.rhs = rhs;
      this.binOp = binOp;
    }

    @Override
    void match(BoolExprMatcher matcher) {
      matcher.binOp(this);
    }

    @Nullable
    private static Boolean computeMaybeConst(BoolExpr lhs, BoolExpr rhs, BinOp binOp) {
      Boolean lhsValue = lhs.maybeConst;
      Boolean rhsValue = rhs.maybeConst;
      if (lhsValue != null) {
        if (lhsValue == (binOp == BinOp.AND)) {
          return rhsValue;
        } else {
          return lhsValue;
        }
      } else if (rhsValue != null) {
        if (rhsValue == (binOp == BinOp.AND)) {
          return lhsValue;
        } else {
          return rhsValue;
        }
      } else {
        return null;
      }
    }

    @Override
    public String toString() {
      return "(" + lhs + " " + binOp + " " + rhs + ")";
    }
  }

  /** Negation. */
  private static class NotExpr extends BoolExpr {
    private final BoolExpr arg;

    NotExpr(Expression expr, BoolExpr arg) {
      super(expr, arg.maybeConst != null ? !arg.maybeConst : null, arg.hasEffects);
      this.arg = arg;
    }

    @Override
    void match(BoolExprMatcher matcher) {
      matcher.not(this);
    }

    @Override
    public String toString() {
      return "not " + arg;
    }
  }

  /** Convert any expression to {@link BoolExpr} optimized for condition compilation. */
  private BoolExpr convert(Expression expr) {
    if (expr instanceof UnaryOperatorExpression
        && ((UnaryOperatorExpression) expr).getOperator() == TokenKind.NOT) {
      BoolExpr simplified = convert(((UnaryOperatorExpression) expr).getX());
      return new NotExpr(expr, simplified);
    }

    if (expr instanceof BinaryOperatorExpression) {
      BinaryOperatorExpression binExpr = (BinaryOperatorExpression) expr;
      if (binExpr.getOperator() == TokenKind.AND || binExpr.getOperator() == TokenKind.OR) {
        BoolExpr lhs = convert(binExpr.getX());
        BoolExpr rhs = convert(binExpr.getY());

        BinOp binOp = binExpr.getOperator() == TokenKind.AND ? BinOp.AND : BinOp.OR;

        return new BinOpExpr(expr, lhs, rhs, binOp);
      }
    }

    BcCompiler.CompileExpressionResultWithIr result = compiler.compileExpression(expr);
    if (result.result.value() != null && BcCompiler.isTruthImmutable(result.result.value())) {
      return new ConstExpr(expr, Starlark.truth(result.result.value()));
    }
    return new OtherExpr(expr, result);
  }
}
