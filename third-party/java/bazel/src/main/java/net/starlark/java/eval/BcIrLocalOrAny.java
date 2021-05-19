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

/** Expression result slot or a marked for any slot. */
abstract class BcIrLocalOrAny {
  private BcIrLocalOrAny() {}

  /** Get a local or make one. */
  abstract BcIrSlot.AnyLocal makeLocal(String label);

  /** Pointer to a local slot. */
  static class Local extends BcIrLocalOrAny {
    final BcIrSlot.AnyLocal local;

    Local(BcIrSlot.AnyLocal local) {
      this.local = local;
    }

    @Override
    BcIrSlot.AnyLocal makeLocal(String label) {
      return local;
    }
  }

  /** Expression is responsible for allocating a slot. */
  static class Any extends BcIrLocalOrAny {
    private Any() {}

    @Override
    BcIrSlot.AnyLocal makeLocal(String label) {
      return new BcIrSlot.LazyLocal(label);
    }

    static final Any ANY = new Any();
  }
}
