/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IntVector.java,v 1.1.1.1 2004/05/09 16:57:53 vlad_r Exp $
 */
package com.vladium.util;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2001
 */
public
final class IntVector implements Cloneable
{
    // public: ................................................................
    
    public IntVector ()
    {
        this (5);
    }
    
    public IntVector (final int initCapacity)
    {
        m_values = new int [initCapacity];
    }
    
    // ACCESSORS:
    
    public int get (final int index)
    {
        if (index > m_size - 1)
            throw new IndexOutOfBoundsException ("get[" + index + "] on vector of size " + m_size);               
        return m_values [index];
    }
    
    public int [] values ()
    {
        if (m_size == 0)
            return IConstants.EMPTY_INT_ARRAY;
        else
        {
            final int size = m_size;
            final int [] result = new int [size];
            
            if (size < COPY_THRESHOLD)
            {
                for (int i = 0; i < size; ++ i) result [i] = m_values [i];
            }
            else
            {
                System.arraycopy (m_values, 0, result, 0, size);
            }
            
            return result;
        }
    }
    
    public int size ()
    {
        return m_size;
    }
    
    // Cloneable:
    
    /**
     * Performs deep copy.
     */
    public Object clone ()
    {
        try
        {
            final IntVector _clone = (IntVector) super.clone ();
            
            // deep clone:
            if (m_size < COPY_THRESHOLD)
            {
                _clone.m_values = new int [m_values.length];
                final int [] _clone_values = _clone.m_values;
                for (int i = 0; i < m_size; ++ i) _clone_values [i] = m_values [i];
            }
            else
            {
                _clone.m_values = (int []) m_values.clone ();
            }
            
            return _clone;
        }
        catch (CloneNotSupportedException e)
        {
            throw new InternalError (e.toString ());
        }
    }
    
    public String toString ()
    {
        final StringBuffer s = new StringBuffer (super.toString() + ", size " + m_size + ": ");
        for (int i = 0; i < m_size; ++ i)
        {
            if (i > 0) s.append (", ");
            s.append (m_values [i]);
        }
        
        return s.toString ();
    }
    
    // MUTATORS:
    
    public int set (final int index, final int value)
    {
        if (index > m_size - 1)
            throw new IndexOutOfBoundsException ("get[" + index + "] on vector of size " + m_size);
        
        final int current_value = m_values [index];
        m_values [index] = value;
        
        return current_value;
    }
    
    public void add (final int value)
    {
        final int capacity = m_values.length;
        if (capacity == m_size)
        {
            final int [] values = new int [1 + (capacity << 1)];
            if (capacity < COPY_THRESHOLD)
            {
                for (int i = 0; i < capacity; ++ i) values [i] = m_values [i];
            }
            else
            {
                System.arraycopy (m_values, 0, values, 0, capacity);
            }
            
            m_values = values;
        }
        
        m_values [m_size ++] = value;
    }    
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private int [] m_values; // never null
    private int m_size;
    
    private static final int COPY_THRESHOLD = 10;

} // end of class
// ----------------------------------------------------------------------------

