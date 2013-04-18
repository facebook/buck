/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ConstantCollection.java,v 1.1.1.1 2004/05/09 16:57:45 vlad_r Exp $
 */
package com.vladium.jcd.cls;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.vladium.jcd.cls.constant.*;
import com.vladium.jcd.lib.UDataOutputStream;
import com.vladium.util.ObjectIntMap;

// ----------------------------------------------------------------------------
/**
 * @author (C) 2001, Vladimir Roubtsov
 */
final class ConstantCollection implements IConstantCollection
{
    // public: ................................................................

    // IConstantCollection:
    
    // ACCESSORS:
        
    public CONSTANT_info get (final int index)
    {
        final Object result = m_constants.get (index - 1);
        
        if (result == null)
            throw new IllegalStateException ("assertion failure: dereferencing an invalid constant pool slot " + index);
        
        return (CONSTANT_info) result;
    }

    public IConstantCollection.IConstantIterator iterator ()
    {
        return new ConstantIterator (m_constants);
    }
    
    public int find (final int type, final IConstantComparator comparator)
    {
        if (comparator == null)
            throw new IllegalArgumentException ("null input: comparator");
        
        for (int i = 0; i < m_constants.size (); ++ i)
        {
            final CONSTANT_info constant = (CONSTANT_info) m_constants.get (i);
            
            if ((constant != null) && (constant.tag () == type) && comparator.equals (constant))
                return i /* !!! */ + 1; 
        }
        
        return -1;
    }
    
    public int findCONSTANT_Utf8 (final String value)
    {
        if (value == null)
            throw new IllegalArgumentException ("null input: value");
        
        // create index lazily:
        final ObjectIntMap index = getCONSTANT_Utf8_index ();
        final int [] result = new int [1];
        
        if (index.get (value, result))
            return result [0] /* !!! */ + 1;
        else
            return -1;
    }
    
    public int size ()
    {
        return m_size;
    }
    
    // Cloneable:
    
    /**
     * Performs a deep copy.
     */
    public Object clone ()
    {
        try
        {
            final ConstantCollection _clone = (ConstantCollection) super.clone ();
            
            // deep copy:
            final int constants_count = m_constants.size ();
            _clone.m_constants = new ArrayList (constants_count);
            for (int c = 0; c < constants_count; ++ c)
            {
                final CONSTANT_info constant = (CONSTANT_info) m_constants.get (c);
                _clone.m_constants.add (constant == null ? null : constant.clone ());
            }
            
            // note: m_CONSTANT_Utf8_index is not cloned intentionally
            
            return _clone;
        }
        catch (CloneNotSupportedException e)
        {
            throw new InternalError (e.toString ());
        }        
    }

    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        final int constant_pool_count = m_constants.size (); // note: this is not the same as size()
        out.writeU2 (constant_pool_count + /* !!! */1);
        
        final ConstantIterator i = new ConstantIterator (m_constants);
        for (CONSTANT_info entry; (entry = i.nextConstant ()) != null; )
        {
            entry.writeInClassFormat (out);
        }
    }
    
    // Visitor:
    
    public void accept (final IClassDefVisitor visitor, final Object ctx)
    {
        visitor.visit (this, ctx);
    }
    
    
    // MUTATORS:
    
    public CONSTANT_info set (final int index, final CONSTANT_info constant)
    {
        final int zindex = index - 1;
        final CONSTANT_info result = (CONSTANT_info) m_constants.get (zindex);
        
        if (result == null)
            throw new IllegalStateException ("assertion failure: dereferencing an invalid constant pool slot " + index);
            
        if (result.width () != constant.width ())
            throw new IllegalArgumentException ("assertion failure: can't set entry of type [" + result.getClass ().getName () + "] to an entry of type [" + result.getClass ().getName () + "] at pool slot " + index);
        
        m_constants.set (zindex, constant);
        
        // update the string index if it is in use:
        if (m_CONSTANT_Utf8_index != null)
        {
            // remove the old index value if it exists and is equal to 'index':
            if (result instanceof CONSTANT_Utf8_info)
            {
                final String mapKey = ((CONSTANT_Utf8_info) result).m_value;
                final int [] out = new int [1];
        
                if (m_CONSTANT_Utf8_index.get (mapKey, out) && (out [0] == zindex))
                    m_CONSTANT_Utf8_index.remove (mapKey);
            }
            
            // add new index value if necessary:
            if (constant instanceof CONSTANT_Utf8_info)
                m_CONSTANT_Utf8_index.put (((CONSTANT_Utf8_info) constant).m_value, zindex);
        }
        
        return result;
    }

    public int add (final CONSTANT_info constant)
    {
        m_constants.add (constant);
        ++ m_size; 
        final int result = m_constants.size ();
        
        for (int width = 1; width < constant.width (); ++ width)
        {
            ++ m_size;
            m_constants.add (null); // insert padding empty slots            
        }
        
        // update the string index if it is in use:
        if ((m_CONSTANT_Utf8_index != null) && (constant instanceof CONSTANT_Utf8_info))
            m_CONSTANT_Utf8_index.put (((CONSTANT_Utf8_info) constant).m_value, result /* !!! */ - 1);
        
        return result;
    }    
        
    // protected: .............................................................
    
    // package: ...............................................................


    ConstantCollection (final int capacity)
    {
        m_constants = capacity < 0 ? new ArrayList () : new ArrayList (capacity);
    }

    // private: ...............................................................

    
    private static final class ConstantIterator implements IConstantCollection.IConstantIterator
    {
        ConstantIterator (final List/* CONSTANT_info */ constants)
        {
            m_constants = constants;
            m_next_index = 1;
            shift ();
        }
        
        
        public int nextIndex ()
        {
            final int result = m_index;
            shift ();
            
            return result;
        }
        
        public CONSTANT_info nextConstant ()
        {
            final int nextIndex = nextIndex ();
            if (nextIndex < 0)
                return null;
            else
                return (CONSTANT_info) m_constants.get (nextIndex - 1);
        }
        
        public CONSTANT_info set (final CONSTANT_info constant)
        {
            final int zindex = m_prev_index - 1;
            final CONSTANT_info result = (CONSTANT_info) m_constants.get (zindex);
        
            if (result == null) // this should never happen with iterators
                throw new IllegalStateException ("assertion failure: dereferencing an invalid constant pool slot " + m_prev_index);
                
            if (result.width () != constant.width ())
                throw new IllegalArgumentException ("assertion failure: can't set entry of type [" + result.getClass ().getName () + "] to an entry of type [" + result.getClass ().getName () + "] at pool slot " + m_prev_index);
            
            m_constants.set (zindex, constant);
            
            return result;
        }
        
        
        private void shift ()
        {
            m_prev_index = m_index;
            m_index = m_next_index;
            
            if (m_index > 0)
            {
                try
                {
                    final CONSTANT_info entry = (CONSTANT_info) m_constants.get (m_index - 1);

                    m_next_index += entry.width ();
                    if (m_next_index > m_constants.size ()) m_next_index = -1;
                }
                catch (IndexOutOfBoundsException ioobe) // empty collection edge case
                {
                    m_index = m_next_index = -1;
                }
            }
        }
        
        
        private int m_index, m_prev_index, m_next_index;
        private List/* CONSTANT_info */ m_constants;
        
    } // end of nested class
    
    
    private ObjectIntMap getCONSTANT_Utf8_index ()
    {
        if (m_CONSTANT_Utf8_index == null)
        {
            final ObjectIntMap index = new ObjectIntMap (m_size);
            
            for (int i = 0; i < m_constants.size (); ++ i)
            {
                final CONSTANT_info constant = (CONSTANT_info) m_constants.get (i);
                
                if ((constant != null) && (constant.tag () == CONSTANT_Utf8_info.TAG))
                {
                    // it's ok to always put: the later indices will simply override the earlier ones
                    index.put (((CONSTANT_Utf8_info) constant).m_value, i); // note: unadjusted index saved here
                }
            }
            
            m_CONSTANT_Utf8_index = index;
        }
        
        return m_CONSTANT_Utf8_index;
    }


    private List/* CONSTANT_info */ m_constants; // never null
    private int m_size;
    private transient ObjectIntMap /* String(CONSTANT_Utf value) -> int(index) */ m_CONSTANT_Utf8_index;
    
} // end of class
// ----------------------------------------------------------------------------
