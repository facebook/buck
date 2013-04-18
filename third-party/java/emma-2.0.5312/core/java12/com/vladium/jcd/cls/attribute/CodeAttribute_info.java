/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: CodeAttribute_info.java,v 1.1.1.1 2004/05/09 16:57:47 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

import java.io.IOException;

import com.vladium.jcd.cls.ElementFactory;
import com.vladium.jcd.cls.IAttributeCollection;
import com.vladium.jcd.cls.IConstantCollection;
import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * The Code attribute is a variable-length attribute used in the attributes
 * table of {@link com.vladium.jcd.cls.Method_info} structures. A Code attribute
 * contains the JVM instructions and auxiliary information for a single Java method,
 * instance initialization method, or class or interface initialization method.
 * Every Java Virtual Machine implementation must recognize Code attributes. There
 * must be exactly one Code attribute in each method_info structure.<P>
 * 
 * The Code attribute has the format
 * <PRE>
 *  Code_attribute {
 *          u2 attribute_name_index;
 *          u4 attribute_length;
 *          u2 max_stack;
 *          u2 max_locals;
 *          u4 code_length;
 *          u1 code[code_length];
 *          u2 exception_table_length;
 *          {            u2 start_pc;
 *                        u2 end_pc;
 *                        u2  handler_pc;
 *                        u2  catch_type;
 *          }        exception_table[exception_table_length];
 *
 *          u2 attributes_count;
 *          attribute_info attributes[attributes_count];
 *  }
 * </PRE>
 *
 * The value of the max_stack item gives the maximum number of words on the operand
 * stack at any point during execution of this method.<P>
 * 
 * The value of the max_locals item gives the number of local variables used by this
 * method, including the parameters passed to the method on invocation. The index of
 * the first local variable is 0 . The greatest local variable index for a one-word
 * value is max_locals-1 . The greatest local variable index for a two-word value is
 * max_locals-2.<P>
 * 
 * The value of the code_length item gives the number of bytes in the code array for
 * this method. The value of code_length must be greater than zero; the code array must
 * not be empty.The code array gives the actual bytes of Java Virtual Machine code that
 * implement the method.<P>
 * 
 * The value of the exception_table_length item gives the number of entries in the
 * exception_table table. Each entry in the exception_table array describes one
 * exception handler in the code array: see {@link Exception_info}.<P>
 * 
 * The value of the attributes_count item indicates the number of attributes of the Code
 * attribute. Each value of the attributes table must be a variable-length attribute
 * structure. A Code attribute can have any number of optional attributes associated
 * with it.
 *  
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class CodeAttribute_info extends Attribute_info
{
    // public: ................................................................


    public static final byte [] EMPTY_BYTE_ARRAY = new byte [0];
    
    public int m_max_stack, m_max_locals;
    
    
    
    public CodeAttribute_info (final int attribute_name_index,
                               final int max_stack, int max_locals,
                               final byte [] code, 
                               final IExceptionHandlerTable exceptionHandlerTable,
                               final IAttributeCollection attributes)
    {
        super (attribute_name_index, 8 + (code != null ? code.length : 0) + exceptionHandlerTable.length () + attributes.length ());
        
        m_max_stack = max_stack;
        m_max_locals = max_locals;
        
        m_code = (code != null ? code : EMPTY_BYTE_ARRAY);
        m_codeSize = m_code.length;
        
        m_exceptionHandlerTable = exceptionHandlerTable;
        m_attributes = attributes;
    }
    
    /**
     * NOTE: must also use getCodeSize() 
     * @return
     */
    public final byte [] getCode ()
    {
        return m_code;
    }
    
    public final int getCodeSize ()
    {
        return m_codeSize;
    }
    
    public IAttributeCollection getAttributes ()
    {
        return m_attributes;
    }
    
    public IExceptionHandlerTable getExceptionTable ()
    {
        return m_exceptionHandlerTable;
    }
    
    public long length ()
    {
        return 14 + m_codeSize + m_exceptionHandlerTable.length () + m_attributes.length ();
    }
    
    // Visitor:
    
    public void accept (final IAttributeVisitor visitor, final Object ctx)
    {
        visitor.visit (this, ctx);
    }
    
    
    public String toString ()
    {
        String eol = System.getProperty ("line.separator");
        
        StringBuffer s = new StringBuffer ();
        
        s.append ("CodeAttribute_info: [attribute_name_index = " + m_name_index + ", attribute_length = " + m_attribute_length + "]" + eol);
        s.append ("    max_stack/max_locals = " + m_max_stack + '/' + m_max_locals + eol);
        s.append ("    code [length " + m_codeSize + "]" + eol);
        
        for (int a = 0; a < m_attributes.size (); ++ a)
        {
            s.append ("         " + m_attributes.get (a) + eol);
        }
       
        
        return s.toString ();
    }
    
    // Cloneable:
    
    /**
     * Performs a deep copy.
     */
    public Object clone ()
    {
        final CodeAttribute_info _clone = (CodeAttribute_info) super.clone ();
        
        // do deep copy:
        
        _clone.m_code = (m_codeSize == 0 ? EMPTY_BYTE_ARRAY : (byte []) m_code.clone ()); // does not trim
        _clone.m_exceptionHandlerTable = (IExceptionHandlerTable) m_exceptionHandlerTable.clone ();
        _clone.m_attributes = (IAttributeCollection) m_attributes.clone ();
        
        return _clone;
    }
    
    
    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        super.writeInClassFormat (out);
        
        out.writeU2 (m_max_stack);
        out.writeU2 (m_max_locals);
    
        out.writeU4 (m_codeSize);
        out.write (m_code, 0, m_codeSize); // TODO: THIS IS WRONG
        
        m_exceptionHandlerTable.writeInClassFormat (out);
        m_attributes.writeInClassFormat (out);
    }
    
    
    public void setCode (final byte [] code, final int codeSize)
    {
        m_code = code;
        m_codeSize = codeSize;
    }
        
    // protected: .............................................................

    // package: ...............................................................


    CodeAttribute_info (final IConstantCollection constants,
                        final int attribute_name_index, final long attribute_length,
                        final UDataInputStream bytes)
        throws IOException
    {
        super (attribute_name_index, attribute_length);
        
        m_max_stack = bytes.readU2 ();
        m_max_locals = bytes.readU2 ();
        
        final long code_length = bytes.readU4 ();
        
        m_code = new byte [(int) code_length];
        bytes.readFully (m_code);
        m_codeSize = (int) code_length;
        
        
        final int exception_table_length = bytes.readU2 ();
        m_exceptionHandlerTable = AttributeElementFactory.newExceptionHandlerTable (exception_table_length);
        
        for (int i = 0; i < exception_table_length; ++ i)
        {
            Exception_info exception_info = new Exception_info (bytes);
            if (DEBUG) System.out.println ("\t[" + i + "] exception: " + exception_info);
            
            m_exceptionHandlerTable.add (exception_info);
        }
        
        
        // TODO: put this logic into AttributeCollection
        final int attributes_count = bytes.readU2 ();
        m_attributes = ElementFactory.newAttributeCollection (attributes_count);
        
        for (int i = 0; i < attributes_count; ++ i)
        {
            Attribute_info attribute_info = Attribute_info.new_Attribute_info (constants, bytes);
            if (DEBUG) System.out.println ("\t[" + i + "] attribute: " + attribute_info);
            
            m_attributes.add (attribute_info);
        }
    }

    // private: ...............................................................


    private byte [] m_code; // never null [valid content extent is m_codeSize]
    private int m_codeSize;
    
    private IExceptionHandlerTable m_exceptionHandlerTable; // never null
    private IAttributeCollection m_attributes; // never null
    
        
    private static final boolean DEBUG = false;
    
} // end of class
// ----------------------------------------------------------------------------
