/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ExceptionsAttribute_info.java,v 1.1.1.1 2004/05/09 16:57:47 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

import java.io.IOException;

import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * The Exceptions attribute is a variable-length attribute used in the attributes
 * table of a {@link com.vladium.jcd.cls.Method_info} structure. The Exceptions
 * attribute indicates which checked exceptions a method may throw. There must be
 * exactly one Exceptions attribute in each method_info structure.<P>
 * 
 * The Exceptions attribute has the following format:
 * <PRE>
 * Exceptions_attribute {
 *          u2 attribute_name_index;
 *          u4 attribute_length;
 *          u2 number_of_exceptions;
 *          u2 exception_index_table[number_of_exceptions];
 *  }
 * </PRE>
 * The value of the number_of_exceptions item indicates the number of entries
 * in the exception_index_table.<P>
 * 
 * Each nonzero value in the exception_index_table array must be a valid index
 * into the constant_pool table. For each table item, if
 * exception_index_table[i] != 0 , where 0 &lt; i &lt; number_of_exceptions,
 * then the constant_pool entry at index exception_index_table[i] must be a
 * {@link com.vladium.jcd.cls.constant.CONSTANT_Class_info} structure representing
 * a class type that this method is declared to throw -- see {@link DeclaredExceptionTable}.
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class ExceptionsAttribute_info extends Attribute_info
{
    // public: ................................................................

    // TODO: merge IDeclaredExceptionTable into this class
    
    public ExceptionsAttribute_info (final int attribute_name_index,
                                     final IDeclaredExceptionTable exceptions)
    {
        super (attribute_name_index, exceptions.length ());
    
        m_exceptions = exceptions;
    }
    
    public IDeclaredExceptionTable getDeclaredExceptions ()
    {
        return m_exceptions;
    }
    
    public long length ()
    {
        return 6 + m_exceptions.length ();
    }
    
    // Visitor:
    
    public void accept (final IAttributeVisitor visitor, final Object ctx)
    {
        visitor.visit (this, ctx);
    }
    
    public String toString ()
    {
        // TODO: return more data here
        return "ExceptionsAttribute_info: [attribute_name_index = " + m_name_index + ", attribute_length = " + m_attribute_length + ']';
    }
    
    // Cloneable:
    
    /**
     * Performs a deep copy.
     */
    public Object clone ()
    {
        final ExceptionsAttribute_info _clone = (ExceptionsAttribute_info) super.clone ();
        
        // do deep copy:
        _clone.m_exceptions = (IDeclaredExceptionTable) m_exceptions.clone ();
        
        return _clone;        
    }
       
    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        super.writeInClassFormat (out);
        
        m_exceptions.writeInClassFormat (out);
    }
    
    // protected: .............................................................
    
    // package: ...............................................................


    
    ExceptionsAttribute_info (final int attribute_name_index, final long attribute_length,
                              final UDataInputStream bytes)
        throws IOException
    {
        super (attribute_name_index, attribute_length);
        
        final int number_of_exceptions = bytes.readU2 ();
        m_exceptions = new DeclaredExceptionTable (number_of_exceptions);
        
        for (int i = 0; i < number_of_exceptions; i++)
        {
            final int exception_index = bytes.readU2 ();
            
            m_exceptions.add (exception_index);
        }
    }
    
    // private: ...............................................................


    private IDeclaredExceptionTable m_exceptions;

} // end of class
// ----------------------------------------------------------------------------
