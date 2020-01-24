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

import com.facebook.buck.core.util.immutables.BuckStyleValue;

/**
 * Represents the LC_SYMTAB command. For reference, see "struct symtab_command" at
 * https://opensource.apple.com/source/xnu/xnu-1699.32.7/EXTERNAL_HEADERS/mach-o/loader.h
 */
@BuckStyleValue
public abstract class MachoSymTabCommand {

  public abstract int getCommand();

  public abstract int getCommandSize();

  public abstract int getSymbolTableOffset();

  public abstract int getNumberOfSymbolTableEntries();

  public abstract int getStringTableOffset();

  public abstract int getStringTableSize();
}
