/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.dx.dex.code;

import com.android.dx.rop.code.RegisterSpec;
import com.android.dx.rop.code.RegisterSpecList;
import com.android.dx.rop.code.SourcePosition;

/**
 * Pseudo-instruction which is used to explicitly end the mapping of a
 * register to a named local variable. That is, an instance of this
 * class in an instruction stream indicates that starting with the
 * subsequent instruction, the indicated variable is no longer valid.
 */
public final class LocalEnd extends ZeroSizeInsn {
    /**
     * {@code non-null;} register spec representing the local variable ended
     * by this instance. <b>Note:</b> Technically, only the register
     * number needs to be recorded here as the rest of the information
     * is implicit in the ambient local variable state, but other code
     * will check the other info for consistency.
     */
    private final RegisterSpec local;

    /**
     * Constructs an instance. The output address of this instance is initially
     * unknown ({@code -1}).
     *
     * @param position {@code non-null;} source position
     * @param local {@code non-null;} register spec representing the local
     * variable introduced by this instance
     */
    public LocalEnd(SourcePosition position, RegisterSpec local) {
        super(position);

        if (local == null) {
            throw new NullPointerException("local == null");
        }

        this.local = local;
    }

    /** {@inheritDoc} */
    @Override
    public DalvInsn withRegisterOffset(int delta) {
        return new LocalEnd(getPosition(), local.withOffset(delta));
    }

    /** {@inheritDoc} */
    @Override
    public DalvInsn withRegisters(RegisterSpecList registers) {
        return new LocalEnd(getPosition(), local);
    }

    /**
     * Gets the register spec representing the local variable ended
     * by this instance.
     *
     * @return {@code non-null;} the register spec
     */
    public RegisterSpec getLocal() {
        return local;
    }

    /** {@inheritDoc} */
    @Override
    protected String argString() {
        return local.toString();
    }

    /** {@inheritDoc} */
    @Override
    protected String listingString0(boolean noteIndices) {
        return "local-end " + LocalStart.localString(local);
    }
}
