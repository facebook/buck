/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IAttributeCollection.java,v 1.1.1.1 2004/05/09 16:57:46 vlad_r Exp $
 */
package com.vladium.jcd.cls;

import com.vladium.jcd.cls.attribute.*;
import com.vladium.jcd.compiler.IClassFormatOutput;

// ----------------------------------------------------------------------------
/**
 * An abstraction of the 'attributes' component of .class format. The contents
 * are {@link Attribute_info} structures. The order in which they appear is
 * unspecified.
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
interface IAttributeCollection extends Cloneable, IClassFormatOutput
{
    // public: ................................................................

    // ACCESSORS:
    
    /**
     * Returns the attribute descriptor at a given offset.
     * 
     * @param offset attribute offset [must be in [0, size()) range; input not checked]
     * @return Attribute_info descriptor [never null]
     * 
     * @throws IndexOutOfBoundsException if 'offset' is outside of valid range
     */
    Attribute_info get (int offset);
    
    boolean hasSynthetic ();
    boolean hasBridge ();
    InnerClassesAttribute_info getInnerClassesAttribute ();
    
    /**
     * Returns the number of attributes in this collection [can be 0].
     */
    int size ();
    
    /**
     * Returns the total length of this collection when converted to
     * .class format [including 2 count bytes]
     */
    long length ();
    
    // Cloneable: adjust the access level of Object.clone():
    Object clone ();
    
    // Visitor:
    void accept (IClassDefVisitor visitor, Object ctx);
    
    
    // MUTATORS:
    
    /**
     * Adds a new Attribute_info descriptor to this collection. No duplicate
     * checks are made. It is the responsibility of the caller to ensure
     * that all data referenced in 'attribute' will eventually appear in the
     * constant pool.
     * 
     * @param attribute new attribute descriptor [may not be null]
     */
    int add (Attribute_info attribute);
    
    /**
     * Replaces the Attribute_info descriptor at a given offset. No duplicate
     * checks are made. It is the responsibility of the caller to ensure that
     * all data referenced in 'attribute' will eventually appear in the constant
     * pool.
     * 
     * @param offset attribute offset [must be in [0, size()) range; input not checked]
     * @param attribute new attribute descriptor [may not be null]
     * @return previous attribute descriptor at this offset [never null]
     * 
     * @throws IndexOutOfBoundsException if 'offset' is outside of valid range
     */
    Attribute_info set (int offset, Attribute_info attribute);
    
    /**
     * Removes the Attribute_info descriptor at a given offset.
     * 
     * @param offset attribute offset [must be in [0, size()) range; input not checked]
     * @return current attribute descriptor at this offset [never null]
     * 
     * @throws IndexOutOfBoundsException if 'offset' is outside of valid range
     */
    Attribute_info remove (int offset);
    
} // end of interface
// ----------------------------------------------------------------------------
