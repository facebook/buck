/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IConstantCollection.java,v 1.1.1.1 2004/05/09 16:57:46 vlad_r Exp $
 */
package com.vladium.jcd.cls;

import com.vladium.jcd.cls.constant.*;
import com.vladium.jcd.compiler.IClassFormatOutput;

// ----------------------------------------------------------------------------
/**
 * An abstraction of constant pool in .class format. This interface disallows
 * any pool mutation that invalidates already existing pool indices. 
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
interface IConstantCollection extends Cloneable, IClassFormatOutput
{
    // public: ................................................................

    /**
     * A custom fail-fast iterator class returned by {@link IConstantCollection#iterator()}.
     * It allows iterating over all entries in a way that steps over all
     * 'invalid' inner slots (extra slots consumed by CONSTANT_Long and
     * CONSTANT_Double entries).
     */
    interface IConstantIterator
    {
        /**
         * Returns the next entry slot index.
         * 
         * @return int next valid slot index [always positive for a valid slot;
         * -1 when the enumeration is exhausted]
         */
        public int nextIndex ();
        
        /**
         * Returns the next entry. This is a convenience method for doing
         * get(nextIndex()) and avoiding index bound violation exceptions.
         * 
         * @return CONSTANT_info next valid entry [null when the enumeration is
         * exhausted]
         */
        public CONSTANT_info nextConstant ();
        
        /**
         * A convenience method that is equivalent to {@link IConstantCollection#set}
         * and replaces the entry that was visited last without invalidating
         * the iterator. 
         */
        CONSTANT_info set (CONSTANT_info constant);
        
    } // end of nested interface
    
    
    /**
     * A simple interface to express custom semantics of constant equality.
     * 
     * @see IConstantCollection#find(int, IConstantComparator)
     */
    interface IConstantComparator
    {
        boolean equals (CONSTANT_info constant);
        
    } // end of nested interface
    
    
    // ACCESSORS:
    
    /**
     * Returns a CONSTANT_info at a given pool index. Note that 'index' is
     * 1-based [the way an index would be embedded in bytecode instructions].
     * Note that because CONSTANT_Long and CONSTANT_Double entries occupy
     * two consequitive index slots certain index values inside the valid range
     * can be invalid; use {@link #iterator()} to iterate only over valid entries
     * in a transparent fashion. 
     * 
     * @param index constant pool index [must be in [1, size()] range]
     * @return CONSTANT_info constant pool entry at this index [never null]
     * 
     * @throws IllegalStateException if an attempt is made to reference
     * an invalid slot index
     * @throws IndexOutOfBoundsException if an attempt is made to reference
     * a slot outside of the valid range 
     */
    CONSTANT_info get (int index);
    
    /**
     * Returns a fail-fast iterator over all valid entries in the pool. The
     * resulting object would be invalidated by simultaneous mutation to the
     * underlying collection pool.
     * 
     * @return IConstantIterator iterator over all entries in the collection [never null]
     */
    IConstantIterator iterator ();
    
    /**
     * Searches the pool for a matching constant of given type with equality
     * semantics expressed by 'comparator'. This method guarantees that
     * when comparator.equals(c) is called c.type() is 'type'. The cost is
     * O(pool size). When multiple matches exist, the location of the first one
     * found will be returned (chosen in some indeterministic way).
     *  
     * @param type type of constants to filter by [not validated]
     * @param comparator [may not be null]
     * @return index of the first found entry [-1 if not found] 
     * 
     * @throws IllegalArgumentException if 'comparator' is null
     */
    int find (int type, IConstantComparator comparator);
    
    /**
     * Convenience method that can lookup CONSTANT_Utf8 entries in O(1) time
     * on average. Note that .class format does not guarantee that all such
     * entries are not duplicated in the pool. When multiple matches exist, the
     * location of the first one found will be returned (chosen in some
     * indeterministic way).
     * 
     * @param value string value on which to match [may not be null]
     * @return index of the first found entry [-1 if not found]
     * 
     * @throws IllegalArgumentException if 'value' is null
     */
    int findCONSTANT_Utf8 (String value);
    
    /**
     * Returns the number of CONSTANT_info entries in this collection. 
     * 
     * @return the number of constants in this pool [can be 0]
     */
    int size ();
        
    // Cloneable: adjust the access level of Object.clone():
    Object clone ();
    
    // Visitor:
    void accept (IClassDefVisitor visitor, Object ctx);
    

    // MUTATORS:
    
    /**
     * Appends 'constant' to the end of the collection. No duplicate checks
     * are made.
     * 
     * @param constant new constant [may not be null; input unchecked]
     * @return the pool index of the newly added entry [always positive]
     */
    int add (CONSTANT_info constant);
    
    /**
     * Replaces an existing constant pool entry. A replacement can be made only
     * for a constant of the same width as the constant currently occupying the
     * slot. 
     * 
     * @param index constant pool index [must be in [1, size()] range]
     * @param constant new entry to set [may not be null; input unchecked]
     * @return CONSTANT_info previous contents at this pool index [never null]
     * 
     * @throws IllegalArgumentException if the new constant's width is different
     * from the current entry's
     * @throws IllegalStateException if an attempt is made to reference
     * an invalid slot index [see {@link #get(int)}]
     * @throws IndexOutOfBoundsException if an attempt is made to reference
     * a slot outside of the valid range
     */
    CONSTANT_info set (int index, CONSTANT_info constant);
    
} // end of interface
// ----------------------------------------------------------------------------
