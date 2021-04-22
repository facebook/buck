package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import net.starlark.java.syntax.TokenKind;

/** Utility to parse bytecode. */
class BcParser {
  private final int[] text;
  private int ip;

  BcParser(int[] text, int ip) {
    this.text = text;
    this.ip = ip;
  }

  BcParser(int[] text) {
    this(text, 0);
  }

  boolean eof() {
    return ip == text.length;
  }

  int remaining() {
    return text.length - ip;
  }

  int[] getText() {
    return text;
  }

  int getIp() {
    return ip;
  }

  int nextInt() {
    Preconditions.checkState(!eof());
    return text[ip++];
  }

  int[] nextInts(int n) {
    int[] r = new int[n];
    for (int i = 0; i != n; ++i) {
      r[i] = nextInt();
    }
    return r;
  }

  int nextListArg() {
    int r = ip;
    int size = nextInt();
    if (size >= 0) {
      Preconditions.checkState(remaining() >= size);
      ip += size;
    }
    return r;
  }

  int lookaheadInt() {
    Preconditions.checkState(!eof());
    return text[ip];
  }

  void skipNArgs() {
    int n = nextInt();
    for (int i = 0; i != n; ++i) {
      nextInt();
    }
  }

  void skipNPairs() {
    int n = nextInt();
    for (int i = 0; i != n; ++i) {
      nextInt();
      nextInt();
    }
  }

  BcInstr.Opcode nextOpcode() {
    return BcInstr.Opcode.fromInt(nextInt());
  }

  TokenKind nextTokenKind() {
    return TokenKind.values()[nextInt()];
  }
}
