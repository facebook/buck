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

package net.starlark.java.annot.internal;

/** Instruction opcodes. */
public enum BcOpcodeNumber {
  // Instruction frequency:
  //
  // CONTINUE              54072231
  // IF_NOT_BR_LOCAL       34227216
  // DOT                   26776754
  // IF_NOT_IN_BR          21075583
  // RETURN                20517769
  // CALL_LINKED_1         19792256
  // LIST                  18523533
  // FOR_INIT              18455440
  // IF_NOT_EQ_BR          16400964
  // IF_BR_LOCAL           14964727
  // CALL_2                13493378
  // CALL_LINKED_2         13403222
  // CALL_1                13205714
  // BR                    12341333
  // IF_NOT_TYPE_IS_BR     11546144
  // RETURN_CONST          10690875
  // SET_INDEX              9640045
  // CP                     8461185
  // EQ                     7137958
  // INDEX                  7130950
  // IF_EQ_BR               7110633
  // CALL_LINKED            6347729
  // CP_LOCAL               6271050
  // LIST_APPEND            6256466
  // PLUS_STRING            6079450
  // IN                     5202096
  // PERCENT_S_ONE          4997522
  // PLUS_LIST              3898241
  // SLICE                  3688547
  // UNPACK                 3483477
  // CALL                   3267990
  // BINARY                 3119850
  // PLUS_STRING_IN_PLACE   2996547
  // RETURN_LOCAL           2902766
  // PLUS_IN_PLACE          2265284
  // IF_TYPE_IS_BR          2211067
  // TUPLE                  2118512
  // UNARY                  1791752
  // PLUS                   1675565
  // CALL_CACHED            1103264
  // RETURN_NONE             981873
  // DICT                    974003
  // NOT_EQ                  919440
  // IF_IN_BR                678312
  // NOT                     356982
  // PLUS_LIST_IN_PLACE      122677
  // TYPE_IS                  77142
  // BREAK                    33631
  // SET_GLOBAL                3410
  // NOT_IN                    2445
  // NEW_FUNCTION              2426
  // LOAD_STMT                 1818
  // PERCENT_S_ONE_TUPLE          0
  // SET_CELL                     0
  // EVAL_EXCEPTION               0

  CP,
  CP_LOCAL,
  RETURN,
  BR,
  IF_BR_LOCAL,
  IF_NOT_BR_LOCAL,
  IF_TYPE_IS_BR,
  IF_NOT_TYPE_IS_BR,
  IF_EQ_BR,
  IF_NOT_EQ_BR,
  IF_IN_BR,
  IF_NOT_IN_BR,
  FOR_INIT,
  CONTINUE,
  BREAK,
  NOT,
  UNARY,
  EQ,
  NOT_EQ,
  IN,
  NOT_IN,
  PLUS,
  PLUS_STRING,
  PLUS_LIST,
  PERCENT_S_ONE,
  PERCENT_S_ONE_TUPLE,
  PLUS_IN_PLACE,
  PLUS_STRING_IN_PLACE,
  PLUS_LIST_IN_PLACE,
  TYPE_IS,
  BINARY,
  SET_GLOBAL,
  SET_CELL,
  DOT,
  INDEX,
  SLICE,
  CALL,
  CALL_1,
  CALL_2,
  CALL_LINKED,
  CALL_LINKED_1,
  CALL_LINKED_2,
  CALL_CACHED,
  LIST,
  TUPLE,
  DICT,
  LIST_APPEND,
  SET_INDEX,
  UNPACK,
  NEW_FUNCTION,
  LOAD_STMT,
  EVAL_EXCEPTION,
}
