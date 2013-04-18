/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: CONSTANT_Integer_info.java,v 1.1.1.1 2004/05/09 16:57:49 vlad_r Exp $
 */
package com.vladium.jcd.cls.constant;

import java.io.IOException;

import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * The CONSTANT_Integer_info and CONSTANT_Float_info structures represent
 * four-byte numeric (int and float) constants.<P>
 * 
 * The bytes item of the CONSTANT_Integer_info structure contains the value of
 * the int constant. The bytes of the value are stored in big-endian (high byte
 * first) order.
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class CONSTANT_Integer_info extends CONSTANT_literal_info
{
    // public: ................................................................

    public static final byte TAG = 3;
    
    public int m_value;
    
    
    public CONSTANT_Integer_info (final int value)
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
        return Integer.toString (m_value);
    }
    
    // Cloneable: inherited clone() is Ok
    
    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        super.writeInClassFormat (out);
        
        out.writeInt (m_value);
    }
    
    // protected: .............................................................

    
    protected CONSTANT_Integer_info (final UDataInputStream bytes) throws IOException
    {
        m_value = bytes.readInt ();
    }
    
    // package: ...............................................................

    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
