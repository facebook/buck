package net.starlark.java.eval;

import com.google.common.base.Verify;
import java.util.Arrays;
import java.util.List;
import net.starlark.java.syntax.LoadStatement;
import net.starlark.java.syntax.Resolver;
import net.starlark.java.syntax.TokenKind;

class BcVisitor {
  private final int[] text;
  private final List<String> strings;
  private final List<Object> constantRegs;
  private final List<Object> objects;

  public BcVisitor(Bc.Compiled bc) {
    this.text = bc.text;
    this.strings = Arrays.asList(bc.strings);
    this.constantRegs = Arrays.asList(bc.constSlots);
    this.objects = Arrays.asList(bc.objects);
  }

  private boolean stop = false;

  /** Early return from {@link #visit()}. */
  protected void stop() {
    stop = true;
  }

  void visit() {
    int ip = 0;
    while (ip != text.length && !stop) {
      BcInstr.Opcode opcode = BcInstr.Opcode.fromInt(text[ip++]);
      int argsLength = opcode.operands.codeSize(text, ip);
      visitInstr(opcode, ip, argsLength);
      ip += argsLength;
    }
  }

  protected void visitInstr(BcInstr.Opcode opcode, int ip, int argsLength) {
    int ipEnd = ip + argsLength;
    switch (opcode) {
      case CP:
        visitCp(text[ip++], text[ip++]);
        break;
      case EQ:
        visitEq(text[ip++], text[ip++], text[ip++]);
        break;
      case NOT_EQ:
        visitNotEq(text[ip++], text[ip++], text[ip++]);
        break;
      case NOT:
        visitNot(text[ip++], text[ip++]);
        break;
      case UNARY:
        visitUnary(text[ip++], TokenKind.values()[text[ip++]], text[ip++]);
        break;
      case BR:
        visitBr(text[ip++]);
        break;
      case IF_BR:
        visitIfBr(text[ip++], text[ip++]);
        break;
      case IF_NOT_BR:
        visitIfNotBr(text[ip++], text[ip++]);
        break;
      case BINARY:
        visitBinary(text[ip++], text[ip++], TokenKind.values()[text[ip++]], text[ip++]);
        break;
      case BINARY_IN_PLACE:
        visitBinaryInPlace(text[ip++], text[ip++], TokenKind.values()[text[ip++]], text[ip++]);
        break;
      case SET_GLOBAL:
        visitSetGlobal(text[ip++], text[ip++], strings.get(text[ip++]), text[ip++]);
        break;
      case SET_CELL:
        visitSetCell(text[ip++], text[ip++]);
        break;
      case DOT:
        visitDot(text[ip++], strings.get(text[ip++]), text[ip++]);
        break;
      case INDEX:
        visitIndex(text[ip++], text[ip++], text[ip++]);
        break;
      case SLICE:
        visitSlice(text[ip++], text[ip++], text[ip++], text[ip++], text[ip++]);
        break;
      case CALL: {
        BcCallLocs callLocs = (BcCallLocs) objects.get(text[ip++]);
        int fn = text[ip++];
        BcDynCallSite callSite = (BcDynCallSite) objects.get(text[ip++]);
        int fnArgsOffset = ip;
        ip += 1 + text[ip];
        int fnStar = text[ip++];
        int fnStarStar = text[ip++];
        int out = text[ip++];
        visitCall(callLocs, fn, callSite, fnArgsOffset, fnStar, fnStarStar, out);
        break;
      }
      case CALL_LINKED: {
        BcCallLocs callLocs = (BcCallLocs) objects.get(text[ip++]);
        StarlarkCallableLinked callableLinked = (StarlarkCallableLinked) objects.get(text[ip++]);
        int fnArgsOffset = ip;
        ip += 1 + text[ip];
        int fnStar = text[ip++];
        int fnStarStar = text[ip++];
        int out = text[ip++];
        visitCallLinked(callLocs, callableLinked, fnArgsOffset, fnStar, fnStarStar, out);
        break;
      }
      case RETURN:
        visitReturn(text[ip++]);
        break;
      case NEW_FUNCTION: {
        Resolver.Function rfn = (Resolver.Function) objects.get(text[ip++]);
        int defaultValues = ip;
        ip += 1 + text[ip];
        int out = text[ip++];
        visitNewFunction(rfn, defaultValues, out);
        break;
      }
      case FOR_INIT:
        visitForInit(text[ip++], text[ip++], text[ip++]);
        break;
      case CONTINUE:
        visitContinue(text[ip++], text[ip++], text[ip++]);
        break;
      case BREAK:
        visitBreak(text[ip++]);
        break;
      case LIST: {
        int args = ip;
        ip += 1 + text[ip];
        int out = text[ip++];
        visitList(args, out);
        break;
      }
      case TUPLE: {
        int args = ip;
        ip += 1 + text[ip];
        int out = text[ip++];
        visitTuple(args, out);
        break;
      }
      case DICT: {
        int args = ip;
        ip += 1 + 2 * text[ip];
        int out = text[ip++];
        visitDict(args, out);
        break;
      }
      case LIST_APPEND:
        visitListAppend(text[ip++], text[ip++]);
        break;
      case SET_INDEX:
        visitSetIndex(text[ip++], text[ip++], text[ip++]);
        break;
      case UNPACK: {
        int in = text[ip++];
        int out = ip;
        ip += 1 + text[ip];
        visitUnpack(in, out);
        break;
      }
      case LOAD_STMT:
        visitLoadStmt((LoadStatement) objects.get(text[ip++]));
        break;
      case EVAL_EXCEPTION:
        visitEvalException(strings.get(text[ip++]));
        break;
      default:
        throw new AssertionError("unknown opcode: " + opcode);
    }
    Verify.verify(ip == ipEnd, "instruction %s decoded incorrectly", opcode);
  }

  protected void unimplemented() {}

  protected void visitInLocal(int local) {
    unimplemented();
  }

  protected void visitInConst(int index, Object constant) {
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
        visitInConst(index, constantRegs.get(index));
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
    for (int i = 0; i != text[ip]; ++i) {
      visitIn(text[ip + i]);
    }
  }

  private void visitNInPairs(int ip) {
    for (int i = 0; i != text[ip]; ++i) {
      visitIn(text[ip + i * 2]);
      visitIn(text[ip + i * 2 + 1]);
    }
  }

  private void visitNOuts(int ip) {
    for (int i = 0; i != text[ip]; ++i) {
      visitOut(text[ip + i]);
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

  protected void visitBinaryInPlace(int in0, int in1, TokenKind op, int out) {
    visitIn(in0);
    visitIn(in1);
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
    visitNIns(argsOffset);
    visitInOrNull(star);
    visitInOrNull(starStar);
    visitOut(out);
  }

  protected void visitCallLinked(BcCallLocs callLocs, StarlarkCallableLinked callableLinked,
      int argsOffset, int star, int starStar, int out) {
    visitNIns(argsOffset);
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
    visitNIns(args);
    visitOut(out);
  }

  protected void visitTuple(int args, int out) {
    visitNIns(args);
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
