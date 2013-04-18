/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IExceptionHandlerTable.java,v 1.1.1.1 2004/05/09 16:57:48 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

import com.vladium.jcd.compiler.IClassFormatOutput;

// ----------------------------------------------------------------------------
/**
 * This table is a structure nested within the {@link CodeAttribute_info}.
 * It is a table of {@link Exception_info} entries, each entry representing an
 * exception handler range. The order of these entries is the order in which
 * a JVM will check for a matching exception handler when the parent method
 * throws an exception.
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
interface IExceptionHandlerTable extends Cloneable, IClassFormatOutput
{
    // public: ................................................................

    // ACCESSORS:
    
    /**
     * Returns {@link Exception_info} descriptor at a given offset.
     * 
     * @param offset exception offset [must be in [0, size()) range; input not checked]
     * @return Exception_info descriptor [never null]
     * 
     * @throws IndexOutOfBoundsException if 'offset' is outside of valid range
     */
    Exception_info get (int offset);
    
    /**
     * Returns the number of descriptors in this collection [can be 0].
     */
    int size ();
    
    /**
     * Returns the total length of this table when converted to
     * .class format [including 2 count bytes]
     */
    long length ();
    
    // Cloneable: adjust the access level of Object.clone():
    Object clone ();


    // MUTATORS:
    
    /**
     * Adds a new Exception_info descriptor to this collection. No duplicate
     * checks are made. It is the responsibility of the caller to ensure
     * that all data referenced in 'exception' will eventually be consistent
     * with method's bytecode and the class's constant pool.
     * 
     * @param exception new exception descriptor [may not be null]
     */
    int add (Exception_info exception);
    
    /**
     * Replaces the Exception_info descriptor at a given offset. No duplicate
     * checks are made. No exception type compatibility checks are made. It is
     * the responsibility of the caller to ensure that all data referenced
     * in 'exception' will eventually be consistent with method's bytecode and
     * the class's constant pool.
     * 
     * @param offset exception offset [must be in [0, size()) range; input not checked]
     * @param exception new exception descriptor [may not be null]
     * @return previous exception descriptor at this offset [never null]
     * 
     * @throws IndexOutOfBoundsException if 'offset' is outside of valid range
     */
    Exception_info set (int offset, Exception_info exception);
    
} // end of interface
// ----------------------------------------------------------------------------
