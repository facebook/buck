/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dx.dex;

import com.android.dex.DexFormat;

import com.android.dx.dex.code.DalvInsnList;

/**
 * Container for options used to control details of dex file generation.
 */
public class DexOptions {

    /**
     * Enable alignment support of 64-bit registers on Dalvik even registers. This is a temporary
     * configuration flag allowing to quickly go back on the default behavior to face up to problem.
     */
    public static final boolean ALIGN_64BIT_REGS_SUPPORT = true;

   /**
    * Does final processing of 64-bit alignment into output finisher to gets output as
    * {@link DalvInsnList} with 64-bit registers aligned at best. Disabled the final processing is
    * required for tools such as Dasm to avoid modifying user inputs.
    */
    public boolean ALIGN_64BIT_REGS_IN_OUTPUT_FINISHER = ALIGN_64BIT_REGS_SUPPORT;

    /** target API level */
    public int targetApiLevel = DexFormat.API_NO_EXTENDED_OPCODES;

    /** force generation of jumbo opcodes */
    public boolean forceJumbo = false;

    /**
     * Gets the dex file magic number corresponding to this instance.
     */
    public String getMagic() {
        return DexFormat.apiToMagic(targetApiLevel);
    }
}
