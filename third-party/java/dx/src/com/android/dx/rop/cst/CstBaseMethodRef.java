/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.dx.rop.cst;

import com.android.dx.rop.type.Prototype;
import com.android.dx.rop.type.Type;
import com.android.dx.rop.type.TypeBearer;

/**
 * Base class for constants of "methodish" type.
 *
 * <p><b>Note:</b> As a {@link TypeBearer}, this class bears the return type
 * of the method.</p>
 */
public abstract class CstBaseMethodRef
        extends CstMemberRef {
    /** {@code non-null;} the raw prototype for this method */
    private final Prototype prototype;

    /**
     * {@code null-ok;} the prototype for this method taken to be an instance
     * method, or {@code null} if not yet calculated
     */
    private Prototype instancePrototype;

    /**
     * Constructs an instance.
     *
     * @param definingClass {@code non-null;} the type of the defining class
     * @param nat {@code non-null;} the name-and-type
     */
    /*package*/ CstBaseMethodRef(CstType definingClass, CstNat nat) {
        super(definingClass, nat);

        String descriptor = getNat().getDescriptor().getString();
        this.prototype = Prototype.intern(descriptor);
        this.instancePrototype = null;
    }

    /**
     * Gets the raw prototype of this method. This doesn't include a
     * {@code this} argument.
     *
     * @return {@code non-null;} the method prototype
     */
    public final Prototype getPrototype() {
        return prototype;
    }

    /**
     * Gets the prototype of this method as either a
     * {@code static} or instance method. In the case of a
     * {@code static} method, this is the same as the raw
     * prototype. In the case of an instance method, this has an
     * appropriately-typed {@code this} argument as the first
     * one.
     *
     * @param isStatic whether the method should be considered static
     * @return {@code non-null;} the method prototype
     */
    public final Prototype getPrototype(boolean isStatic) {
        if (isStatic) {
            return prototype;
        } else {
            if (instancePrototype == null) {
                Type thisType = getDefiningClass().getClassType();
                instancePrototype = prototype.withFirstParameter(thisType);
            }
            return instancePrototype;
        }
    }

    /** {@inheritDoc} */
    @Override
    protected final int compareTo0(Constant other) {
        int cmp = super.compareTo0(other);

        if (cmp != 0) {
            return cmp;
        }

        CstBaseMethodRef otherMethod = (CstBaseMethodRef) other;
        return prototype.compareTo(otherMethod.prototype);
    }

    /**
     * {@inheritDoc}
     *
     * In this case, this method returns the <i>return type</i> of this method.
     *
     * @return {@code non-null;} the method's return type
     */
    public final Type getType() {
        return prototype.getReturnType();
    }

    /**
     * Gets the number of words of parameters required by this
     * method's descriptor. Since instances of this class have no way
     * to know if they will be used in a {@code static} or
     * instance context, one has to indicate this explicitly as an
     * argument. This method is just a convenient shorthand for
     * {@code getPrototype().getParameterTypes().getWordCount()},
     * plus {@code 1} if the method is to be treated as an
     * instance method.
     *
     * @param isStatic whether the method should be considered static
     * @return {@code >= 0;} the argument word count
     */
    public final int getParameterWordCount(boolean isStatic) {
        return getPrototype(isStatic).getParameterTypes().getWordCount();
    }

    /**
     * Gets whether this is a reference to an instance initialization
     * method. This is just a convenient shorthand for
     * {@code getNat().isInstanceInit()}.
     *
     * @return {@code true} iff this is a reference to an
     * instance initialization method
     */
    public final boolean isInstanceInit() {
        return getNat().isInstanceInit();
    }

    /**
     * Gets whether this is a reference to a class initialization
     * method. This is just a convenient shorthand for
     * {@code getNat().isClassInit()}.
     *
     * @return {@code true} iff this is a reference to an
     * instance initialization method
     */
    public final boolean isClassInit() {
        return getNat().isClassInit();
    }
}
