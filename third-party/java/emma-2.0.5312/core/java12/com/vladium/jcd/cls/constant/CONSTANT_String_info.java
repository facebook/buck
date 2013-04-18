/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: CONSTANT_String_info.java,v 1.1.1.1 2004/05/09 16:57:49 vlad_r Exp $
 */
package com.vladium.jcd.cls.constant;

import java.io.IOException;

import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * The CONSTANT_String_info structure is used to represent constant objects of
 * the type java.lang.String.<P>
 * 
 * The value of the string_index item must be a valid index into the constant pool
 * table. The constant pool entry at that index must be a {@link CONSTANT_Utf8_info}
 * structure representing the sequence of characters to which the
 * java.lang.String object is to be initialized.
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public 
final class CONSTANT_String_info extends CONSTANT_literal_info
{
    // public: ................................................................

    public static final byte TAG = 8;
    
    public int m_string_index;
    
    
    public CONSTANT_String_info (final int string_index)
    {
        m_string_index = string_index;
    }


    public final byte tag ()
    {
        return TAG;
    }
    
    // Visitor:
    
    public Object accept (final ICONSTANTVisitor visitor, final Object ctx)
    {
        return visitor.visit (this, ctx);
    }
    
    public String toString ()
    {
        return "CONSTANT_String: [string_index = " + m_string_index + ']';
    }
    
    // Cloneable: inherited clone() is Ok
    
    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        super.writeInClassFormat (out);
        
        out.writeU2 (m_string_index);    
    }
    
    // protected: .............................................................

    
    protected CONSTANT_String_info (final UDataInputStream bytes) throws IOException
    {
        m_string_index = bytes.readU2 ();
    }
    
    // package: ...............................................................

    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
