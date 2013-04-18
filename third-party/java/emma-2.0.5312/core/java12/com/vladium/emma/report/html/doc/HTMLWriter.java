/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: HTMLWriter.java,v 1.1.1.1 2004/05/09 16:57:41 vlad_r Exp $
 */
package com.vladium.emma.report.html.doc;

import java.io.IOException;
import java.io.Writer;

import com.vladium.util.IConstants;
import com.vladium.util.asserts.$assert;
import com.vladium.emma.IAppErrorCodes;
import com.vladium.emma.EMMARuntimeException;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class HTMLWriter
{
    // public: ................................................................
    
    // TODO: add API for indenting
    
    public HTMLWriter (final Writer out)
    {
        if (out == null) throw new IllegalArgumentException ("null input: out");
        
        m_out = out;
    }
    
    
    public void write (final String s)
    {
        if ($assert.ENABLED) $assert.ASSERT (s != null, "s = null");
        
        if (m_out != null)
        {
            try
            {
                m_out.write (s);
            }
            catch (IOException ioe)
            {
                throw new EMMARuntimeException (IAppErrorCodes.REPORT_IO_FAILURE, ioe);
            }
        }
    }
    
    public void write (final char c)
    {
        if (m_out != null)
        {
            try
            {
                m_out.write (c);
            }
            catch (IOException ioe)
            {
                throw new EMMARuntimeException (IAppErrorCodes.REPORT_IO_FAILURE, ioe);
            }
        }
    }
    
    public void eol ()
    {
        if (m_out != null)
        {
            try
            {
                m_out.write (IConstants.EOL);
            }
            catch (IOException ioe)
            {
                throw new EMMARuntimeException (IAppErrorCodes.REPORT_IO_FAILURE, ioe);
            }
        }
    }
    
    public void flush ()
    {
        if (m_out != null)
        {
            try
            {
                m_out.flush ();
            }
            catch (IOException ioe)
            {
                throw new EMMARuntimeException (IAppErrorCodes.REPORT_IO_FAILURE, ioe);
            }
        }
    }
    
    public void close ()
    {
        if (m_out != null)
        {
            try { m_out.close (); } catch (IOException ignore) {}
            m_out = null;
        }
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private Writer m_out;

} // end of class
// ----------------------------------------------------------------------------