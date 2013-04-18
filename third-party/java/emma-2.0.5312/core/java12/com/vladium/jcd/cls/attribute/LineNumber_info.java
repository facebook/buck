/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: LineNumber_info.java,v 1.1.1.1 2004/05/09 16:57:48 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

import java.io.IOException;

import com.vladium.jcd.compiler.IClassFormatOutput;
import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * This class represents a line_number_table entry contained by
 * {@link LineNumberTableAttribute_info} attribute. Each entry contains the
 * following items:
 * <PRE>
 *    start_pc
 * </PRE>
 * The value of the start_pc item must indicate the index into the code array
 * at which the code for a new line in the original source file begins. The
 * value of start_pc must be less than the value of the code_length item of
 * the {@link CodeAttribute_info} attribute of which this LineNumberTable
 * is an attribute.<P>
 * <PRE>
 *    line_number
 * </PRE>
 * The value of the line_number item must give the corresponding line number
 * in the original source file.
 * 
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class LineNumber_info implements Cloneable, IClassFormatOutput
{
    // public: ................................................................
    
    public int m_start_pc, m_line_number;
    
    
    public LineNumber_info (final int start_pc, final int line_number)
    {
        m_start_pc = start_pc;
        m_line_number = line_number;
    }
    
    public String toString ()
    {
        return "line_number_info: [start_pc = " + m_start_pc + ", line_number = " + m_line_number + "]";
    }    
    
    // Cloneable:
    
    /**
     * Performs a deep copy.
     */
    public Object clone ()
    {
        try
        {    
            return super.clone ();
        }
        catch (CloneNotSupportedException e)
        {
            throw new InternalError (e.toString ());
        }        
    }

    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        out.writeU2 (m_start_pc);
        out.writeU2 (m_line_number);
    }
    
    // protected: .............................................................
    
    // package: ...............................................................


    LineNumber_info (final UDataInputStream bytes) throws IOException
    {
        m_start_pc = bytes.readU2 ();
        m_line_number = bytes.readU2 ();
    }
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------