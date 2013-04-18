/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ExceptionHandlerTable.java,v 1.1.1.1 2004/05/09 16:57:47 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * @author (C) 2001, Vlad Roubtsov
 */
final class ExceptionHandlerTable implements IExceptionHandlerTable
{
    // public: ................................................................

    // ACCESSORS:
    
    public Exception_info get (final int offset)
    {
        return (Exception_info) m_exceptions.get (offset);
    }
    
    public int size ()
    {
        return m_exceptions.size ();
    }
    
    public long length ()
    {
        return 2 + (m_exceptions.size () << 3); // use size() if class becomes non-final
    }
        
    // Cloneable:
    
    /**
     * Performs a deep copy.
     */
    public Object clone ()
    {
        try
        {
            final ExceptionHandlerTable _clone = (ExceptionHandlerTable) super.clone ();
            
            // deep clone:
            final int exceptions_count = m_exceptions.size (); // use size() if class becomes non-final
            _clone.m_exceptions = new ArrayList (exceptions_count);
            for (int e = 0; e < exceptions_count; ++ e)
            {
                _clone.m_exceptions.add (((Exception_info) m_exceptions.get (e)).clone ());
            }
            
            return _clone;
        }
        catch (CloneNotSupportedException e)
        {
            throw new InternalError (e.toString ());
        }        
    }
    
    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        int exception_table_length = m_exceptions.size (); // use size() if class becomes non-final
        out.writeU2 (exception_table_length);
        
        for (int i = 0; i < exception_table_length; i++)
        {
            get (i).writeInClassFormat (out);
        }
    }


    // MUTATORS:

    public int add (final Exception_info exception)
    {
        final int newoffset = m_exceptions.size (); // use size() if class becomes non-final
        m_exceptions.add (exception);
        
        return newoffset;
    }
    
    public Exception_info set (final int offset, final Exception_info exception)
    {
        return (Exception_info) m_exceptions.set (offset, exception);
    }
    
    // protected: .............................................................

    // package: ...............................................................


    ExceptionHandlerTable (final int capacity)
    {
        m_exceptions = capacity < 0 ? new ArrayList () : new ArrayList (capacity);
    }

    // private: ...............................................................


    private List/* Exception_info */ m_exceptions;
    
} // end of class
// ----------------------------------------------------------------------------
