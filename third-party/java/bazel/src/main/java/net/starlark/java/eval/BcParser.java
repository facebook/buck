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
