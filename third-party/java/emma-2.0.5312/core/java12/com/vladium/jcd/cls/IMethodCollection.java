/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IMethodCollection.java,v 1.1.1.1 2004/05/09 16:57:46 vlad_r Exp $
 */
package com.vladium.jcd.cls;

import com.vladium.jcd.compiler.IClassFormatOutput;

// ----------------------------------------------------------------------------
/**
 * An abstraction of the 'methods' component of .class format. The contents
 * are {@link Method_info} structures corresponding to all methods directly
 * declared by this class/interface. The order in which they appear is
 * unspecified.
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
interface IMethodCollection extends Cloneable, IClassFormatOutput
{
    // public: ................................................................

    // ACCESSORS:
    
    /**
     * Returns {@link Method_info} descriptor at a given offset.
     * 
     * @param offset method offset [must be in [0, size()) range; input not checked]
     * @return Method_info descriptor [never null]
     * 
     * @throws IndexOutOfBoundsException if 'offset' is outside of valid range
     */
    Method_info get (int offset);
    
    /**
     * Returns an array of offsets for methods named 'name' (empty array if no
     * matching fields found).
     * 
     * @param cls class definition providing the constant pool against which to
     * resolve names [may not be null]
     * @param name method name [null or empty will result in no matches]
     * @return array of method offsets in no particular order [never null; could be empty]
     * 
     * @throws IllegalArgumentException if 'cls' is null
     */
    int [] get (ClassDef cls, String name);
    
    /**
     * Returns the number of methods in this collection [can be 0].
     */
    int size ();

    // Cloneable: adjust the access level of Object.clone():
    Object clone ();
    
    // Visitor:
    void accept (IClassDefVisitor visitor, Object ctx);

    
    // MUTATORS:
    
    /**
     * Adds a new Method_info descriptor to this collection. No duplicate
     * checks are made. It is the responsibility of the caller to ensure
     * that all data referenced in 'method' will eventually appear in the
     * constant pool.
     * 
     * @param method new method descriptor [may not be null]
     */
    int add (Method_info method);
    
    /**
     * Replaces the Method_info descriptor at a given offset. No duplicate
     * checks are made. No method type compatibility checks are made.  It is
     * the responsibility of the caller to ensure that all data referenced
     * in 'method' will eventually appear in the constant pool.
     * 
     * @param offset method offset [must be in [0, size()) range; input not checked]
     * @param method new method descriptor [may not be null]
     * @return previous method descriptor at this offset [never null]
     * 
     * @throws IndexOutOfBoundsException if 'offset' is outside of valid range
     */
    Method_info set (int offset, Method_info method);
    
    // TODO: support this via iterators instead
    /**
     * Removes the Method_info descriptor at a given offset. It is
     * the responsibility of the caller to ensure that the class definition
     * remains consistent after this change.
     * 
     * @param offset method offset [must be in [0, size()) range; input not checked]
     * @return method descriptor at this offset [never null]
     * 
     * @throws IndexOutOfBoundsException if 'offset' is outside of valid range
     */
    Method_info remove (int offset);
    
} // end of interface
// ----------------------------------------------------------------------------
