/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IDeclaredExceptionTable.java,v 1.1.1.1.2.1 2004/07/10 03:34:52 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

import com.vladium.jcd.compiler.IClassFormatOutput;

// ----------------------------------------------------------------------------
/**
 * This table is a structure nested within {@link ExceptionsAttribute_info}
 * structure. It is a table of unsigned 16-bit indexes into constant pool. Each
 * index points to a {@link com.vladium.jcd.cls.constant.CONSTANT_Class_info}
 * entry representing an exception a method can throw [in unspecified order].
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
interface IDeclaredExceptionTable extends Cloneable, IClassFormatOutput
{
    // public: ................................................................

    // ACCESSORS:
    
    /**
     * Returns the {@link com.vladium.jcd.cls.constant.CONSTANT_Class_info} constant
     * pool index for offset'th exception type thrown by the method that contains
     * this this exception index table in its ExceptionsAttribute_info attribute.
     * 
     * @param offset thrown exception class number [must be in [0, size()) range]
     * @return constant pool index [always positive]  
     * 
     * @throws IndexOutOfBoundsException if 'offset' is outside of valid range
     */
    int get (int offset);
    
    /**
     * Returns the number of exception types the containing method professes
     * to throw.
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
     * Appends a new exception class pointer to the collection. No duplicate checks
     * are made.
     * 
     * @param exception_index constant pool index [must be positive; input not validated]
     * @return offset of the new pointer [same as {@link #size()}-1 when called
     * after this method] 
     */
    int add (int exception_index);
    
    /**
     * Replaces exception class pointer number 'offset' with new value 'interface_index'.
     * No duplicate checks are made. It is the responsibility of the caller to
     * ensure that the relevant CONSTANT_Class_info descriptor will be found
     * in the constant pool, in the slot pointed to by 'exception_index'.
     * 
     * @param offset thrown exception class number [must be in [0, size()) range]
     * @param exception_index constant pool index [must be positive; input not validated]
     * @return previous value at the given index [always positive]
     * 
     * @throws IndexOutOfBoundsException if 'offset' is outside of valid range
     */
    int set (int offset, int exception_index);
    
} // end of interface
// ----------------------------------------------------------------------------
