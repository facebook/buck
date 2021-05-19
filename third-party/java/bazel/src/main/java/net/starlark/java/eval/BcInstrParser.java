/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import net.starlark.java.syntax.TokenKind;

/** Utility to parse bytecode. */
class BcInstrParser {
  private final int[] text;
  private int ip;

  BcInstrParser(int[] text, int ip) {
    this.text = text;
    this.ip = ip;
  }

  BcInstrParser(int[] text) {
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

  BcInstrOpcode nextOpcode() {
    return BcInstrOpcode.fromInt(nextInt());
  }

  /** Consume next opcode if it is equal to given opcode. */
  boolean nextOpcodeIf(BcInstrOpcode opcode) {
    if (eof()) {
      return false;
    }
    if (text[ip] != opcode.ordinal()) {
      return false;
    }
    ip++;
    return true;
  }

  TokenKind nextTokenKind() {
    return TokenKind.values()[nextInt()];
  }
}
