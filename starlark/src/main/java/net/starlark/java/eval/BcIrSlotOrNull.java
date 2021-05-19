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

/** Either slot or a "null" register. */
abstract class BcIrSlotOrNull {
  private BcIrSlotOrNull() {}

  @Override
  public abstract String toString();

  abstract int encode(BcIrWriteContext writeContext);

  static class Null extends BcIrSlotOrNull {
    private Null() {}

    @Override
    public String toString() {
      return Null.class.getSimpleName();
    }

    @Override
    int encode(BcIrWriteContext writeContext) {
      return BcSlot.NULL_FLAG;
    }

    static final Null NULL = new Null();
  }

  static class Slot extends BcIrSlotOrNull {
    final BcIrSlot slot;

    Slot(BcIrSlot slot) {
      this.slot = slot;
    }

    @Override
    public String toString() {
      return slot.toString();
    }

    @Override
    int encode(BcIrWriteContext writeContext) {
      return slot.encode(writeContext);
    }
  }
}
