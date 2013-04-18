/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: GenericAttribute_info.java,v 1.1.1.1 2004/05/09 16:57:48 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

import java.io.IOException;

import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * This attribute structure is used during parsing to absorb all attribute types
 * that are not currently recognized.
 * 
 * @see Attribute_info
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class GenericAttribute_info extends Attribute_info
{
    // public: ................................................................

    
    public byte [] m_info;
    
    
    public GenericAttribute_info (final int attribute_name_index, final byte [] info)
    {
        super (attribute_name_index, (info != null ? info.length : 0));
        
        m_info = (info != null ? info : EMPTY_BYTE_ARRAY);
    }
    
    
    public long length ()
    {
        return 6 + m_info.length; 
    }
    
    // Visitor:
    
    public void accept (final IAttributeVisitor visitor, final Object ctx)
    {
        visitor.visit (this, ctx);
    }
    
    public String toString ()
    {
        return "generic attribute_info: [attribute_name_index = " + m_name_index + ", attribute_length = " + m_attribute_length + ']';
    }
    
    // Cloneable:
    
    /**
     * Performs a deep copy.
     */
    public Object clone ()
    {
        final GenericAttribute_info _clone = (GenericAttribute_info) super.clone ();
        
        // do deep copy:
        _clone.m_info = (m_info.length == 0 ? EMPTY_BYTE_ARRAY : (byte []) m_info.clone ());
        
        return _clone;
    }
    
    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        super.writeInClassFormat (out);
        
        out.write (m_info, 0, m_info.length);
    }
    
    // protected: .............................................................
    
    // package: ...............................................................


    GenericAttribute_info (final int attribute_name_index, final long attribute_length,
                           final UDataInputStream bytes)
        throws IOException
    {
        super (attribute_name_index, attribute_length);
        
        m_info = new byte [(int) m_attribute_length];
        bytes.readFully (m_info);
    }

    // private: ...............................................................

    
    private static final byte [] EMPTY_BYTE_ARRAY = new byte [0];

} // end of class
// ----------------------------------------------------------------------------
