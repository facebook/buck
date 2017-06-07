/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.macho;

import com.google.common.primitives.UnsignedInteger;

/**
 * Symbolic debugger symbols. The comments give the conventional use for
 *
 * <p>.stabs "n_name", n_type, n_sect, n_desc, n_value
 *
 * <p>where n_type is the defined constant and not listed in the comment. Other fields not listed
 * are zero. n_sect is the section ordinal the entry is referring to.
 */
public class Stab {
  private Stab() {}

  /** source file name: name,,n_sect,0,address */
  public static final UnsignedInteger N_SO = UnsignedInteger.fromIntBits(0x64);
  /** object file name: name,,0,0,st_mtime */
  public static final UnsignedInteger N_OSO = UnsignedInteger.fromIntBits(0x66);
  /** #included file name: name,,n_sect,0,address */
  public static final UnsignedInteger N_SOL = UnsignedInteger.fromIntBits(0x84);
}
