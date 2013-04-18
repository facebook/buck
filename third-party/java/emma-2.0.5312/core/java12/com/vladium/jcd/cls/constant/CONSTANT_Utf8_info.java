/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: CONSTANT_Utf8_info.java,v 1.1.1.1 2004/05/09 16:57:49 vlad_r Exp $
 */
package com.vladium.jcd.cls.constant;

import java.io.IOException;

import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * The CONSTANT_Utf8_info structure is used to represent constant string values.<P>
 * 
 * The bytes of multibyte characters are stored in the class file in big-endian
 * (high byte first) order. There are two differences between this format and the
 * "standard" UTF-8 format. First, the null byte (byte)0 is encoded using the
 * two-byte format rather than the one-byte format, so that Java Virtual Machine
 * UTF-8 strings never have embedded nulls. Second, only the one-byte, two-byte,
 * and three-byte formats are used. The Java Virtual Machine does not recognize
 * the longer UTF-8 formats.  
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class CONSTANT_Utf8_info extends CONSTANT_info
{
    // public: ................................................................

    public static final byte TAG = 1;
    
    public String m_value;
    
    
    public CONSTANT_Utf8_info (final String value)
    {
        m_value = value;
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
        return "CONSTANT_Utf8: [" + m_value + ']';
    }
       
    // Cloneable: inherited clone() is Ok
    
    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        super.writeInClassFormat (out);
        
        out.writeUTF (m_value);
    }
    
    // protected: .............................................................

    
    protected CONSTANT_Utf8_info (final UDataInputStream bytes) throws IOException
    {
        m_value = bytes.readUTF ();
    }
    
    // package: ...............................................................

    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
