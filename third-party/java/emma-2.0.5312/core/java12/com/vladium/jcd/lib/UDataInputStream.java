/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: UDataInputStream.java,v 1.1.1.1.2.1 2004/07/10 03:34:53 vlad_r Exp $
 */
package com.vladium.jcd.lib;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

// ----------------------------------------------------------------------------
/**
 * A trivial extension to java.io.DataInputStream to provide methods for
 * reading unsigned 16- and 32-bit integers with simple mnemonics. It uses
 * correspondingly wider native types to preserve the full range of the unsigned
 * types.
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class UDataInputStream extends DataInputStream
{
    // public: ................................................................

    
    public UDataInputStream (final InputStream _in)
    {
        super (_in);
    }

    
    public final int readU2 () throws IOException
    {
        final short value = readShort ();
        
        return ((int) value) & 0xFFFF; // widening cast sign-extends
    }
    
    
    public final long readU4 () throws IOException
    {
        final int value = readInt ();
        
        return ((long) value) & 0xFFFFFFFFL; // widening cast sign-extends
    }
    
    // protected: .............................................................

    // package: ...............................................................

    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
