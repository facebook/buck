/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ByteArrayIStream.java,v 1.1.1.1 2004/05/09 16:57:51 vlad_r Exp $
 */
package com.vladium.util;

import java.io.InputStream;

import com.vladium.util.asserts.$assert;

// ----------------------------------------------------------------------------
/**
 * An unsynchronized version of java.io.ByteArrayInputStream.<p>
 * 
 * All argument validation is disabled in release mode.<p>
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class ByteArrayIStream extends InputStream
{
    // public: ................................................................


    public ByteArrayIStream (final byte [] buf)
    {
        this (buf, buf.length);
    }

    public ByteArrayIStream (final byte [] buf, final int length)
    {
        if ($assert.ENABLED) $assert.ASSERT ((length >= 0) && (length <= buf.length),
            "invalid length: " + length);
        
        m_buf = buf;
        m_max = length;
    }    

    // InputStream:
    
    public final int read ()
    {
        if (m_pos >= m_max)
            return -1;
        else
            return 0xFF & m_buf [m_pos ++];
    }

    public final int read (final byte [] buf, final int offset, int length)
    {
        if ($assert.ENABLED)
            $assert.ASSERT ((offset >= 0) && (offset <= buf.length) &&
                (length >= 0) && ((offset + length) <= buf.length),
                "invalid input (" + buf.length + ", " + offset + ", " + length + ")");
        
        final int pos = m_pos;
        final int max = m_max;
        
        if (pos >= max) return -1;
        if (pos + length > max) length = max - pos;
        if (length <= 0) return 0;
        
        final byte [] mbuf = m_buf;
        
        if (length < NATIVE_COPY_THRESHOLD)
            for (int i = 0; i < length; ++ i) buf [offset + i] = mbuf [pos + i];
        else
            System.arraycopy (mbuf, pos, buf, offset, length);
            
        m_pos += length;
        
        return length;
    }
    
    public final int available ()
    {
        return m_max - m_pos;
    }
    
    public final long skip (long n)
    {
        if (m_pos + n > m_max) n = m_max - m_pos;

        if (n < 0) return 0;        
        m_pos += n;
        
        return n;
    }
    
    /**
     * Differs from the contruct for InputStream.reset() in that this method
     * always resets the stream to the same it was immediately after creation.
     */
    public final void reset ()
    {
        m_pos = 0;
    }

    /**
     * Equivalent to {@link #reset()}. 
     */
    public final void close ()
    {
        reset ();
    }

    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private final byte [] m_buf;
    private final int m_max;
    private int m_pos;

    private static final int NATIVE_COPY_THRESHOLD  = 9;

} // end of class
// ----------------------------------------------------------------------------