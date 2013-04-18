/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ByteArrayOStream.java,v 1.1.1.1 2004/05/09 16:57:52 vlad_r Exp $
 */
package com.vladium.util;

import java.io.IOException;
import java.io.OutputStream;

import com.vladium.util.asserts.$assert;

// ----------------------------------------------------------------------------
/**
 * An unsynchronized version of java.io.ByteArrayOutputStream that can expose
 * the underlying byte array without a defensive clone and can also be converted
 * to a {@link ByteArrayIStream} without intermediate array copies.<p> 
 * 
 * All argument validation is disabled in release mode.<p>
 * 
 * NOTE: copy-on-write not supported
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class ByteArrayOStream extends OutputStream
{
    // public: ................................................................
    
    /**
     * Callee takes ownership of 'buf'.
     */
    public ByteArrayOStream (final int initialCapacity)
    {
        if ($assert.ENABLED)
            $assert.ASSERT (initialCapacity >= 0, "negative initial capacity: " + initialCapacity); 
        
        m_buf = new byte [initialCapacity];
    }
    
    public final ByteArrayIStream toByteIStream ()
    {
        return new ByteArrayIStream (m_buf, m_pos);
    }
    
    public final void write2 (final int b1, final int b2)
    {
        final int pos = m_pos;
        final int capacity = pos + 2;
        byte [] mbuf = m_buf;
        final int mbuflen = mbuf.length;
        
        if (mbuflen < capacity)
        {
            final byte [] newbuf = new byte [Math.max (mbuflen << 1, capacity)];
        
            if (pos < NATIVE_COPY_THRESHOLD)
                for (int i = 0; i < pos; ++ i) newbuf [i] = mbuf [i];
            else
                System.arraycopy (mbuf, 0, newbuf, 0, pos);
            
            m_buf = mbuf = newbuf;
        }
        
        mbuf [pos] = (byte) b1;
        mbuf [pos + 1] = (byte) b2;
        m_pos = capacity;
    }
    
    public final void write3 (final int b1, final int b2, final int b3)
    {
        final int pos = m_pos;
        final int capacity = pos + 3;
        byte [] mbuf = m_buf;
        final int mbuflen = mbuf.length;
        
        if (mbuflen < capacity)
        {
            final byte [] newbuf = new byte [Math.max (mbuflen << 1, capacity)];
        
            if (pos < NATIVE_COPY_THRESHOLD)
                for (int i = 0; i < pos; ++ i) newbuf [i] = mbuf [i];
            else
                System.arraycopy (mbuf, 0, newbuf, 0, pos);
            
            m_buf = mbuf = newbuf;
        }
        
        mbuf [pos] = (byte) b1;
        mbuf [pos + 1] = (byte) b2;
        mbuf [pos + 2] = (byte) b3;
        m_pos = capacity;
    }
    
    public final void write4 (final int b1, final int b2, final int b3, final int b4)
    {
        final int pos = m_pos;
        final int capacity = pos + 4;
        byte [] mbuf = m_buf;
        final int mbuflen = mbuf.length;
        
        if (mbuflen < capacity)
        {
            final byte [] newbuf = new byte [Math.max (mbuflen << 1, capacity)];
        
            if (pos < NATIVE_COPY_THRESHOLD)
                for (int i = 0; i < pos; ++ i) newbuf [i] = mbuf [i];
            else
                System.arraycopy (mbuf, 0, newbuf, 0, pos);
            
            m_buf = mbuf = newbuf;
        }
        
        mbuf [pos] = (byte) b1;
        mbuf [pos + 1] = (byte) b2;
        mbuf [pos + 2] = (byte) b3;
        mbuf [pos + 3] = (byte) b4;
        m_pos = capacity;
    }
    
    public final void writeTo (final OutputStream out)
        throws IOException
    {
        out.write (m_buf, 0, m_pos);
    }
    
//    public final void readFully (final InputStream in)
//        throws IOException
//    {
//        while (true)
//        {
//            int chunk = in.available ();
//            
//            System.out.println ("available = " + chunk);
//            
//            // TODO: this case is handled poorly (on EOF)
//            if (chunk == 0) chunk = READ_CHUNK_SIZE;
//            
//            // read at least 'available' bytes: extend the capacity as needed
//        
//            int free = m_buf.length - m_pos;
//            
//            final int read;
//            if (free > chunk)
//            {
//                // try reading more than 'chunk' anyway:
//                read = in.read (m_buf, m_pos, free);
//            }
//            else
//            {
//                // extend the capacity to match 'chunk':
//                {
//                    System.out.println ("reallocation");
//                    final byte [] newbuf = new byte [m_pos + chunk];
//            
//                    if (m_pos < NATIVE_COPY_THRESHOLD)
//                        for (int i = 0; i < m_pos; ++ i) newbuf [i] = m_buf [i];
//                    else
//                        System.arraycopy (m_buf, 0, newbuf, 0, m_pos);
//                
//                    m_buf = newbuf;
//                }
//                
//                read = in.read (m_buf, m_pos, chunk);
//            }
//
//            if (read < 0)
//                break;
//            else
//                m_pos += read;
//        }
//    }
    
//    public final void addCapacity (final int extraCapacity)
//    {
//        final int pos = m_pos;
//        final int capacity = pos + extraCapacity;
//        byte [] mbuf = m_buf;
//        final int mbuflen = mbuf.length;
//        
//        if (mbuflen < capacity)
//        {
//            final byte [] newbuf = new byte [Math.max (mbuflen << 1, capacity)];
//        
//            if (pos < NATIVE_COPY_THRESHOLD)
//                for (int i = 0; i < pos; ++ i) newbuf [i] = mbuf [i];
//            else
//                System.arraycopy (mbuf, 0, newbuf, 0, pos);
//            
//            m_buf = newbuf;
//        }
//    }
    
    public final byte [] getByteArray ()
    {
        return m_buf;
    }

    /**
     * 
     * @return [result.length = size()]
     */    
    public final byte [] copyByteArray ()
    {
        final int pos = m_pos;
        
        final byte [] result = new byte [pos];
        final byte [] mbuf = m_buf;
        
        if (pos < NATIVE_COPY_THRESHOLD)
            for (int i = 0; i < pos; ++ i) result [i] = mbuf [i];
        else
            System.arraycopy (mbuf, 0, result, 0, pos);
        
        return result;
    }
    
    public final int size ()
    {
        return m_pos;
    }
    
    public final int capacity ()
    {
        return m_buf.length;
    }
    
    /**
     * Does not reduce the current capacity.
     */
    public final void reset ()
    {
        m_pos = 0;
    }
    
    // OutputStream:
    
    public final void write (final int b)
    {
        final int pos = m_pos;
        final int capacity = pos + 1;
        byte [] mbuf = m_buf;
        final int mbuflen = mbuf.length;
        
        if (mbuflen < capacity)
        {
            final byte [] newbuf = new byte [Math.max (mbuflen << 1, capacity)];
            
            if (pos < NATIVE_COPY_THRESHOLD)
                for (int i = 0; i < pos; ++ i) newbuf [i] = mbuf [i];
            else
                System.arraycopy (mbuf, 0, newbuf, 0, pos);
            
            m_buf = mbuf = newbuf;
        }
        
        mbuf [pos] = (byte) b;
        m_pos = capacity;
    }


    public final void write (final byte [] buf, final int offset, final int length)
    {
        if ($assert.ENABLED)
            $assert.ASSERT ((offset >= 0) && (offset <= buf.length) &&
                (length >= 0) && ((offset + length) <= buf.length),
                "invalid input (" + buf.length + ", " + offset + ", " + length + ")");
        
        final int pos = m_pos;
        final int capacity = pos + length;
        byte [] mbuf = m_buf;
        final int mbuflen = mbuf.length;
        
        if (mbuflen < capacity)
        {
            final byte [] newbuf = new byte [Math.max (mbuflen << 1, capacity)];
            
            if (pos < NATIVE_COPY_THRESHOLD)
                for (int i = 0; i < pos; ++ i) newbuf [i] = mbuf [i];
            else
                System.arraycopy (mbuf, 0, newbuf, 0, pos);
            
            m_buf = mbuf = newbuf;
        }
        
        if (length < NATIVE_COPY_THRESHOLD)
            for (int i = 0; i < length; ++ i) mbuf [pos + i] = buf [offset + i];
        else
            System.arraycopy (buf, offset, mbuf, pos, length);
            
        m_pos = capacity; 
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
    
    
    private byte [] m_buf;
    private int m_pos;
    
//    private static final int READ_CHUNK_SIZE        = 16 * 1024;
    private static final int NATIVE_COPY_THRESHOLD  = 9;

} // end of class
// ----------------------------------------------------------------------------