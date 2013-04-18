/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: UDataOutputStream.java,v 1.1.1.1.2.1 2004/07/10 03:34:53 vlad_r Exp $
 */
package com.vladium.jcd.lib;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

// ----------------------------------------------------------------------------
/**
 * A trivial extension to java.io.DataInputStream to provide methods for
 * writing unsigned 16- and 32-bit integers with simple mnemonics. It uses
 * correspondingly wider native types to preserve the full range of the unsigned
 * types.
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class UDataOutputStream extends DataOutputStream
{
    // public: ................................................................

    
    public UDataOutputStream (final OutputStream _out)
    {
        super (_out);
    }
    
    
    public final void writeU2 (final int uint) throws IOException
    {
        writeShort ((short) uint); // this narrowing cast is Ok
    }
    
    
    public final void writeU4 (final long ulong) throws IOException
    {
        writeInt ((int) ulong); // this narrowing cast is Ok
    }
    
    // protected: .............................................................

    // package: ...............................................................

    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
