package net.starlark.java.eval;

import com.google.common.base.Verify;
import java.util.Arrays;
import java.util.List;
import net.starlark.java.syntax.LoadStatement;
import net.starlark.java.syntax.Resolver;
import net.starlark.java.syntax.TokenKind;

class BcVisitor {
  private final BcParser parser;
  private final List<String> strings;
  private final List<Object> constantRegs;
  private final List<Object> objects;
  private final BcCompiled bc;

  public BcVisitor(BcCompiled bc) {
    this.parser = new BcParser(bc.text);
    this.strings = Arrays.asList(bc.strings);
    this.constantRegs = Arrays.asList(bc.constSlots);
    this.objects = Arrays.asList(bc.objects);
    this.bc = bc;
  }

  private boolean stop = false;

  /** Early return from {@link #visit()}. */
  protected void stop() {
    stop = true;
  }

  void visit() {
    while (!parser.eof() && !stop) {
      BcInstr.Opcode opcode = parser.nextOpcode();
      visitInstr(opcode, parser);
    }
  }

  protected void visitInstr(BcInstr.Opcode opcode, BcParser parser) {
    int expectedIpEnd = parser.getIp() + opcode.operands.codeSize(bc.text, parser.getIp());
    switch (opcode) {
      case CP:
        visitCp(parser.nextInt(), parser.nextInt());
        break;
      case EQ:
        visitEq(parser.nextInt(), parser.nextInt(), parser.nextInt());
        break;
      case NOT_EQ:
        visitNotEq(parser.nextInt(), parser.nextInt(), parser.nextInt());
        break;
      case PLUS:
      case PLUS_STRING:
        visitPlus(parser.nextInt(), parser.nextInt(), parser.nextInt());
        break;
      case PLUS_LIST:
        visitPlusListConst(parser.nextInt(), parser.nextListArg(), parser.nextInt());
        break;
      case IN:
        visitBinaryIn(parser.nextInt(), parser.nextInt(), parser.nextInt());
        break;
      case NOT_IN:
        visitBinaryNotIn(parser.nextInt(), parser.nextInt(), parser.nextInt());
        break;
      case NOT:
        visitNot(parser.nextInt(), parser.nextInt());
        break;
      case UNARY:
        visitUnary(parser.nextInt(), TokenKind.values()[parser.nextInt()], parser.nextInt());
        break;
      case BR:
        visitBr(parser.nextInt());
        break;
      case IF_BR:
        visitIfBr(parser.nextInt(), parser.nextInt());
        break;
      case IF_NOT_BR:
        visitIfNotBr(parser.nextInt(), parser.nextInt());
        break;
      case BINARY:
        visitBinary(parser.nextInt(), parser.nextInt(), TokenKind.values()[parser.nextInt()], parser.nextInt());
        break;
      case PLUS_IN_PLACE:
      case PLUS_STRING_IN_PLACE:
        visitPlusInPlace(parser.nextInt(), parser.nextInt(), parser.nextInt());
        break;
      case PLUS_LIST_IN_PLACE:
        visitPlusListInPlace(parser.nextInt(), parser.nextListArg(), parser.nextInt());
        break;
      case PERCENT_S_ONE:
        visitPercentSOne(parser.nextInt(), parser.nextInt(), parser.nextInt(), parser.nextInt());
        break;
      case PERCENT_S_ONE_TUPLE:
        visitPercentSOneTuple(parser.nextInt(), parser.nextInt(), parser.nextInt(), parser.nextInt());
        break;
      case SET_GLOBAL:
        visitSetGlobal(parser.nextInt(), parser.nextInt(), strings.get(parser.nextInt()), parser.nextInt());
        break;
      case SET_CELL:
        visitSetCell(parser.nextInt(), parser.nextInt());
        break;
      case DOT:
        visitDot(parser.nextInt(), strings.get(parser.nextInt()), parser.nextInt());
        break;
      case INDEX:
        visitIndex(parser.nextInt(), parser.nextInt(), parser.nextInt());
        break;
      case SLICE:
        visitSlice(parser.nextInt(), parser.nextInt(), parser.nextInt(), parser.nextInt(), parser.nextInt());
        break;
      case CALL: {
        BcCallLocs callLocs = (BcCallLocs) objects.get(parser.nextInt());
        int fn = parser.nextInt();
        BcDynCallSite callSite = (BcDynCallSite) objects.get(parser.nextInt());
        int fnArgsOffset = parser.nextListArg();
        int fnStar = parser.nextInt();
        int fnStarStar = parser.nextInt();
        int out = parser.nextInt();
        visitCall(callLocs, fn, callSite, fnArgsOffset, fnStar, fnStarStar, out);
        break;
      }
      case CALL_LINKED: {
        BcCallLocs callLocs = (BcCallLocs) objects.get(parser.nextInt());
        StarlarkCallableLinked callableLinked = (StarlarkCallableLinked) objects.get(parser.nextInt());
        int fnArgsOffset = parser.nextListArg();
        int fnStar = parser.nextInt();
        int fnStarStar = parser.nextInt();
        int out = parser.nextInt();
        visitCallLinked(callLocs, callableLinked, fnArgsOffset, fnStar, fnStarStar, out);
        break;
      }
      case RETURN:
        visitReturn(parser.nextInt());
        break;
      case NEW_FUNCTION: {
        Resolver.Function rfn = (Resolver.Function) objects.get(parser.nextInt());
        int defaultValues = parser.getIp();
        parser.skipNArgs();
        int out = parser.nextInt();
        visitNewFunction(rfn, defaultValues, out);
        break;
      }
      case FOR_INIT:
        visitForInit(parser.nextInt(), parser.nextInt(), parser.nextInt());
        break;
      case CONTINUE:
        visitContinue(parser.nextInt(), parser.nextInt(), parser.nextInt());
        break;
      case BREAK:
        visitBreak(parser.nextInt());
        break;
      case LIST: {
        visitList(parser.nextListArg(), parser.nextInt());
        break;
      }
      case TUPLE: {
        visitTuple(parser.nextListArg(), parser.nextInt());
        break;
      }
      case DICT: {
        int args = parser.getIp();
        parser.skipNPairs();
        int out = parser.nextInt();
        visitDict(args, out);
        break;
      }
      case LIST_APPEND:
        visitListAppend(parser.nextInt(), parser.nextInt());
        break;
      case SET_INDEX:
        visitSetIndex(parser.nextInt(), parser.nextInt(), parser.nextInt());
        break;
      case UNPACK: {
        int in = parser.nextInt();
        int out = parser.getIp();
        parser.skipNArgs();
        visitUnpack(in, out);
        break;
      }
      case LOAD_STMT:
        visitLoadStmt((LoadStatement) objects.get(parser.nextInt()));
        break;
      case EVAL_EXCEPTION:
        visitEvalException(strings.get(parser.nextInt()));
        break;
      default:
        throw new AssertionError("unknown opcode: " + opcode);
    }
    Verify.verify(parser.getIp() == expectedIpEnd, "instruction %s decoded incorrectly", opcode);
  }

  protected void unimplemented() {}

  protected void visitInLocal(int local) {
    unimplemented();
  }

  protected void visitInConst(Object constant) {
    unimplemented();
  }

  protected void visitInFree(int index) {
    unimplemented();
  }

  protected void visitInGlobal(int index) {
    unimplemented();
  }

  protected void visitInCell(int index) {
    unimplemented();
  }

  protected void visitIn(int in) {
    BcSlot.checkValidSourceSlot(in);
    int index = in & ~BcSlot.MASK;
    switch (in & BcSlot.MASK) {
      case BcSlot.LOCAL_FLAG:
        visitInLocal(index);
        break;
      case BcSlot.GLOBAL_FLAG:
        visitInGlobal(index);
        break;
      case BcSlot.CELL_FLAG:
        visitInCell(index);
        break;
      case BcSlot.FREE_FLAG:
        visitInFree(index);
        break;
      case BcSlot.CONST_FLAG:
        Verify.verify(index < constantRegs.size());
        visitInConst(constantRegs.get(index));
        break;
      default:
        throw new AssertionError("unreachable");
    }
  }

  protected void visitInOrNull(int in) {
    if (in != BcSlot.NULL_FLAG) {
      visitIn(in);
    }
  }

  protected void visitOut(int out) {
    BcSlot.checkLocal(out);
  }

  protected void visitJmpDest(int addr) {
    unimplemented();
  }

  private void visitNIns(int ip) {
    BcParser parser = new BcParser(this.parser.getText(), ip);
    int n = parser.nextInt();
    for (int i = 0; i != n; ++i) {
      visitIn(parser.nextInt());
    }
  }

  protected void visitInListArg(int listArgIp) {
    int size = parser.getText()[listArgIp];
    if (size < 0) {
      Object[] consts = (Object[]) objects.get(BcSlot.negativeSizeToObjectIndex(size));
      for (Object object : consts) {
        visitInConst(object);
      }
    } else {
      visitNIns(listArgIp);
    }
  }

  private void visitNInPairs(int ip) {
    BcParser parser = new BcParser(this.parser.getText(), ip);
    int n = parser.nextInt();
    for (int i = 0; i != n; ++i) {
      visitIn(parser.nextInt());
      visitIn(parser.nextInt());
    }
  }

  private void visitNOuts(int ip) {
    BcParser parser = new BcParser(this.parser.getText(), ip);
    int n = parser.nextInt();
    for (int i = 0; i != n; ++i) {
      visitOut(parser.nextInt());
    }
  }

  protected void visitCp(int in, int out) {
    visitIn(in);
    visitOut(out);
  }

  protected void visitEq(int in0, int in1, int out) {
    visitIn(in0);
    visitIn(in1);
    visitOut(out);
  }

  protected void visitNotEq(int in0, int in1, int out) {
    visitIn(in0);
    visitIn(in1);
    visitIn(out);
  }

  protected void visitPlus(int in0, int in1, int out) {
    visitIn(in0);
    visitIn(in1);
    visitIn(out);
  }

  protected void visitPlusListConst(int lhs, int listArg, int out) {
    visitIn(lhs);
    visitInListArg(listArg);
    visitOut(out);
  }

  protected void visitBinaryIn(int in0, int in1, int out) {
    visitIn(in0);
    visitIn(in1);
    visitIn(out);
  }

  protected void visitBinaryNotIn(int in0, int in1, int out) {
    visitIn(in0);
    visitIn(in1);
    visitIn(out);
  }

  protected void visitNot(int in, int out) {
    visitIn(in);
    visitOut(out);
  }

  protected void visitUnary(int in, TokenKind op, int out) {
    visitIn(in);
    visitOut(out);
  }

  protected void visitBr(int addr) {
    visitJmpDest(addr);
  }

  protected void visitIfBr(int cond, int addr) {
    visitIn(cond);
    visitJmpDest(addr);
  }

  protected void visitIfNotBr(int cond, int addr) {
    visitIn(cond);
    visitJmpDest(addr);
  }

  protected void visitBinary(int in0, int in1, TokenKind op, int out) {
    visitIn(in0);
    visitIn(in1);
    visitOut(out);
  }

  protected void visitPlusInPlace(int in0, int in1, int out) {
    visitIn(in0);
    visitIn(in1);
    visitOut(out);
  }

  protected void visitPlusListInPlace(int lhs, int list, int out) {
    visitIn(lhs);
    visitInListArg(list);
    visitOut(out);
  }

  protected void visitPercentSOne(int format, int percent, int in, int out) {
    visitIn(in);
    visitOut(out);
  }

  protected void visitPercentSOneTuple(int format, int percent, int in, int out) {
    visitIn(in);
    visitOut(out);
  }

  protected void visitSetGlobal(int in, int globalIndex, String name, int postAssignHook) {
    visitIn(in);
  }

  protected void visitSetCell(int in, int cellIndex) {
    visitIn(in);
  }

  protected void visitDot(int in, String field, int out) {
    visitIn(in);
    visitOut(out);
  }

  protected void visitIndex(int lhs, int index, int out) {
    visitIn(lhs);
    visitIn(index);
    visitOut(out);
  }

  protected void visitSlice(int lhs, int a, int b, int c, int out) {
    visitIn(lhs);
    visitInOrNull(a);
    visitInOrNull(b);
    visitInOrNull(c);
    visitOut(out);
  }

  protected void visitCall(BcCallLocs callLocs, int fn,
      BcDynCallSite callSite, int argsOffset, int star, int starStar, int out) {
    visitIn(fn);
    visitInListArg(argsOffset);
    visitInOrNull(star);
    visitInOrNull(starStar);
    visitOut(out);
  }

  protected void visitCallLinked(BcCallLocs callLocs, StarlarkCallableLinked callableLinked,
      int argsOffset, int star, int starStar, int out) {
    visitInListArg(argsOffset);
    visitInOrNull(star);
    visitInOrNull(starStar);
    visitOut(out);
  }

  protected void visitReturn(int in) {
    visitInOrNull(in);
  }

  protected void visitNewFunction(Resolver.Function rfn, int defaultValues, int out) {
    visitNIns(defaultValues);
    visitOut(out);
  }

  protected void visitForInit(int in, int out, int e) {
    visitIn(in);
    visitOut(out);
    visitJmpDest(e);
  }

  protected void visitContinue(int out, int b, int e) {
    visitOut(out);
    visitJmpDest(b);
    visitJmpDest(e);
  }

  protected void visitBreak(int e) {
    visitJmpDest(e);
  }

  protected void visitList(int args, int out) {
    visitInListArg(args);
    visitOut(out);
  }

  protected void visitTuple(int args, int out) {
    visitInListArg(args);
    visitOut(out);
  }

  protected void visitDict(int args, int out) {
    visitNInPairs(args);
    visitOut(out);
  }

  protected void visitListAppend(int list, int item) {
    visitIn(list);
    visitIn(item);
  }

  protected void visitSetIndex(int dict, int key, int value) {
    visitIn(dict);
    visitIn(key);
    visitIn(value);
  }

  protected void visitUnpack(int in, int outsIp) {
    visitIn(in);
    visitNOuts(outsIp);
  }

  protected void visitLoadStmt(LoadStatement loadStatement) {
    unimplemented();
  }

  protected void visitEvalException(String s) {
    unimplemented();
  }
}
