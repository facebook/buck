/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: CONSTANT_Long_info.java,v 1.1.1.1 2004/05/09 16:57:49 vlad_r Exp $
 */
package com.vladium.jcd.cls.constant;

import java.io.IOException;

import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * The CONSTANT_Long_info and {@link CONSTANT_Double_info} represent eight-byte
 * numeric (long and double) constants.<P>
 * 
 * The unsigned high_bytes and low_bytes items of the CONSTANT_Long_info structure
 * together contain the value of the long constant
 * (( long ) high_bytes << 32) + low_bytes , where the bytes of each of high_bytes
 * and low_bytes are stored in big-endian (high byte first) order.
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class CONSTANT_Long_info extends CONSTANT_literal_info
{
    // public: ................................................................

    public static final byte TAG = 5;
    
    public long m_value;
    
    
    public CONSTANT_Long_info (final long value)
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
        return Long.toString (m_value);
    }
    
    /**
     * Overrides the default implementation to return '2'.
     */
    public int width ()
    {
        return 2;
    }
    
    // Cloneable: inherited clone() is Ok
    
    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        super.writeInClassFormat (out);
        
        out.writeLong (m_value);
    }
    
    // protected: .............................................................

    
    protected CONSTANT_Long_info (final UDataInputStream bytes) throws IOException
    {
        m_value = bytes.readLong ();
    }
    
    // package: ...............................................................

    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
