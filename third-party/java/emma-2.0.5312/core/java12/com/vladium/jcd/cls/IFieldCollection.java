/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IFieldCollection.java,v 1.1.1.1 2004/05/09 16:57:46 vlad_r Exp $
 */
package com.vladium.jcd.cls;

import com.vladium.jcd.compiler.IClassFormatOutput;

// ----------------------------------------------------------------------------
/**
 * An abstraction of the 'fields' component of .class format. The contents
 * are {@link Field_info} structures corresponding to all fields directly
 * declared by this class/interface. The order in which they appear is
 * unspecified.
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
interface IFieldCollection extends Cloneable, IClassFormatOutput
{
    // public: ................................................................
    
    // ACCESSORS:
    
    /**
     * Returns {@link Field_info} descriptor at a given offset.
     * 
     * @param offset field offset [must be in [0, size()) range; input not checked]
     * @return Field_info descriptor [never null]
     * 
     * @throws IndexOutOfBoundsException if 'offset' is outside of valid range
     */
    Field_info get (int offset);
    
    /**
     * Returns an array of offsets for fields named 'name' (empty array if no
     * matching fields found). Note: even though Java syntax disallows for a class
     * to have multiple fields with the same name it is possible at the bytecode
     * level (as long as the type descriptors disambiguate).
     * 
     * @param cls class definition providing the constant pool against which to
     * resolve names [may not be null]
     * @param name field name [null or empty will result in no matches]
     * @return array of field offsets in no particular order [never null; could be empty]
     * 
     * @throws IllegalArgumentException if 'cls' is null
     */
    int [] get (ClassDef cls, String name);
    
    /**
     * Returns the number of fields in this collection [can be 0].
     */
    int size ();

    // Cloneable: adjust the access level of Object.clone():
    Object clone ();
    
    // Visitor:
    void accept (IClassDefVisitor visitor, Object ctx);


    // MUTATORS:
    
    /**
     * Adds a new Field_info descriptor to this collection. No duplicate
     * checks are made. It is the responsibility of the caller to ensure
     * that all data referenced in 'field' will eventually appear in the
     * constant pool.
     * 
     * @param field new field descriptor [may not be null]
     * @return new field's offset
     */
    int add (Field_info field);
    
    /**
     * Replaces the Field_info descriptor at a given offset. No duplicate
     * checks are made. No field type compatibility checks are made.  It is
     * the responsibility of the caller to ensure that all data referenced
     * in 'field' will eventually appear in the constant pool.
     * 
     * @param offset field offset [must be in [0, size()) range; input not checked]
     * @param field new field descriptor [may not be null]
     * @return previous field descriptor at this offset [never null]
     * 
     * @throws IndexOutOfBoundsException if 'offset' is outside of valid range
     */
    Field_info set (int offset, Field_info field);
        
} // end of interface
// ----------------------------------------------------------------------------
