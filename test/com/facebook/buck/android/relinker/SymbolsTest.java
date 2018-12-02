/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.android.relinker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SymbolsTest {
  @Test
  public void testSymbolExtraction() {
    Symbols.SymbolInfo si;
    // CHECKSTYLE.OFF: LineLength
    si =
        Symbols.extractSymbolInfo(
            "/opt/android_ndk/r12b/sources/third_party/vulkan/src/build-android/jniLibs/armeabi-v7a/libVkLayer_core_validation.so:     file format elf32-littlearm");
    // CHECKSTYLE.ON: LineLength
    assertNull(si);
    si = Symbols.extractSymbolInfo("");
    assertNull(si);
    si = Symbols.extractSymbolInfo("DYNAMIC SYMBOL TABLE:");
    assertNull(si);

    si = Symbols.extractSymbolInfo("00000000      DF *UND*  00000000 __cxa_finalize");
    assertNotNull(si);
    assertTrue(si.isUndefined);
    assertFalse(si.isGlobal);
    assertEquals(si.symbol, "__cxa_finalize");

    si = Symbols.extractSymbolInfo("00000000      DF *UND*  00000000 strlen");
    assertNotNull(si);
    assertTrue(si.isUndefined);
    assertFalse(si.isGlobal);
    assertEquals(si.symbol, "strlen");

    si = Symbols.extractSymbolInfo("00174a8c g    DF .text  0000000a __aeabi_unwind_cpp_pr0");
    assertNotNull(si);
    assertFalse(si.isUndefined);
    assertTrue(si.isGlobal);
    assertEquals(si.symbol, "__aeabi_unwind_cpp_pr0");

    si = Symbols.extractSymbolInfo("00000000      DF *UND*  00000000 LIBC   strlen");
    assertNotNull(si);
    assertTrue(si.isUndefined);
    assertFalse(si.isGlobal);
    assertEquals(si.symbol, "strlen");

    si =
        Symbols.extractSymbolInfo("00174a8c g    DF .text  0000000a BASE   __aeabi_unwind_cpp_pr0");
    assertNotNull(si);
    assertFalse(si.isUndefined);
    assertTrue(si.isGlobal);
    assertEquals(si.symbol, "__aeabi_unwind_cpp_pr0");

    si =
        Symbols.extractSymbolInfo(
            "00000000002cde9c g     F .text     00000004 _ZN5folly6detail27annotate_rwlock_create_implEPVKvPKci");
    assertNotNull(si);
    assertTrue(si.isGlobal);
    assertFalse(si.isUndefined);
    assertEquals(si.symbol, "_ZN5folly6detail27annotate_rwlock_create_implEPVKvPKci");
  }
}
