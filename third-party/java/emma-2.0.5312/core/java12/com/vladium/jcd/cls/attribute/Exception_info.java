/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Exception_info.java,v 1.1.1.1 2004/05/09 16:57:48 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

import java.io.IOException;

import com.vladium.jcd.compiler.IClassFormatOutput;
import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * An Exception_info is an entry layout format for {@link ExceptionHandlerTable}. Each
 * entry contains the following items: 
 * <PRE>
 *    start_pc, end_pc 
 * </PRE>
 * The values of the two items start_pc and end_pc indicate the ranges in the code
 * array at which the exception handler is active. The value of start_pc must be
 * a valid index into the code array of the opcode of an instruction. The value of
 * end_pc either must be a valid index into the code array of the opcode of an
 * instruction, or must be equal to code_length , the length of the code array.
 * The value of start_pc must be less than the value of end_pc.<P>
 * 
 * The start_pc is inclusive and end_pc is exclusive; that is, the exception handler
 * must be active while the program counter is within the interval [start_pc, end_pc).
 * <PRE>
 *    handler_pc 
 * </PRE>
 * The value of the handler_pc item indicates the start of the exception handler.
 * The value of the item must be a valid index into the code array, must be the index
 * of the opcode of an instruction, and must be less than the value of the code_length
 * item.
 * <PRE>
 *    catch_type
 * </PRE>
 * If the value of the catch_type item is nonzero, it must be a valid index into the
 * constant_pool table. The constant_pool entry at that index must be a
 * {@link com.vladium.jcd.cls.constant.CONSTANT_Class_info} structure representing
 * a class of exceptions that this exception handler is designated to catch.
 * This class must be the class Throwable or one of its subclasses. The exception
 * handler will be called only if the thrown exception is an instance of the given
 * class or one of its subclasses.<P>
 * 
 * If the value of the catch_type item is zero, this exception handler is called for
 * all exceptions. This is used to implement finally. 
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class Exception_info implements Cloneable, IClassFormatOutput
{
    // public: ................................................................

    
    public int m_start_pc, m_end_pc, m_handler_pc, m_catch_type;
    
    
    public Exception_info (final int start_pc, final int end_pc,
                           final int handler_pc, final int catch_type)
    {
        m_start_pc = start_pc;
        m_end_pc = end_pc;
        m_handler_pc = handler_pc;
        m_catch_type = catch_type;
    }
    
    
    public String toString ()
    {
        return "exception_info: [start_pc/end_pc = " + m_start_pc + '/' + m_end_pc +
               ", handler_pc = " + m_handler_pc +
               ", catch_type = " + m_catch_type + ']';
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
        out.writeU2 (m_end_pc);
        out.writeU2 (m_handler_pc);
        out.writeU2 (m_catch_type);
    }
    
    // protected: .............................................................

    // package: ...............................................................


    Exception_info (final UDataInputStream bytes) throws IOException
    {
        m_start_pc = bytes.readU2 ();
        m_end_pc = bytes.readU2 ();
        m_handler_pc = bytes.readU2 ();
        m_catch_type = bytes.readU2 ();
    }

    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
