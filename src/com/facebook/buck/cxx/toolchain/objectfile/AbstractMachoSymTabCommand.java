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

package com.facebook.buck.cxx.toolchain.objectfile;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

/**
 * Represents the LC_SYMTAB command. For reference, see "struct symtab_command" at
 * https://opensource.apple.com/source/xnu/xnu-1699.32.7/EXTERNAL_HEADERS/mach-o/loader.h
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractMachoSymTabCommand {
  @Value.Parameter
  abstract int getCommand();

  @Value.Parameter
  abstract int getCommandSize();

  @Value.Parameter
  abstract int getSymbolTableOffset();

  @Value.Parameter
  abstract int getNumberOfSymbolTableEntries();

  @Value.Parameter
  abstract int getStringTableOffset();

  @Value.Parameter
  abstract int getStringTableSize();
}
