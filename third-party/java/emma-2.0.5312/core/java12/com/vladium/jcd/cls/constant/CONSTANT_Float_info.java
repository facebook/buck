/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: CONSTANT_Float_info.java,v 1.1.1.1 2004/05/09 16:57:48 vlad_r Exp $
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
 * The bytes item of the CONSTANT_Float_info structure contains the value of
 * the float constant in IEEE 754 floating-point "single format" bit layout.
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class CONSTANT_Float_info extends CONSTANT_literal_info
{
    // public: ................................................................

    public static final byte TAG = 4;
    
    public float m_value;
    
    
    public CONSTANT_Float_info (final float value)
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
        return Float.toString (m_value);
    }
    
    // Cloneable: inherited clone() is Ok
    
    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        super.writeInClassFormat (out);
        
        out.writeFloat (m_value);
    }
    
    // protected: .............................................................

    
    protected CONSTANT_Float_info (final UDataInputStream bytes) throws IOException
    {
        m_value = bytes.readFloat ();
    }
    
    // package: ...............................................................

    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
