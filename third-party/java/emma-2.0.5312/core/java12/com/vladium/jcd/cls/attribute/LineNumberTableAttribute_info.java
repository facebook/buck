/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: LineNumberTableAttribute_info.java,v 1.1.1.1 2004/05/09 16:57:48 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * The LineNumberTable attribute is an optional variable-length attribute in
 * the attributes table of a {@link CodeAttribute_info} attribute. It may be
 * used by debuggers to determine which part of the JVM code array corresponds
 * to a given line number in the original source file. If LineNumberTable
 * attributes are present in the attributes table of a given Code attribute,
 * then they may appear in any order. Furthermore, multiple LineNumberTable
 * attributes may together represent a given line of a source file; that is,
 * LineNumberTable attributes need not be one-to-one with source lines.<P>
 * 
 * The LineNumberTable attribute has the following format:
 * <PRE>
 * LineNumberTable_attribute {
 *          u2 attribute_name_index;
 *          u4 attribute_length;
 *          u2 line_number_table_length;
 *          {  u2 start_pc;             
 *             u2 line_number;             
 *          } line_number_table[line_number_table_length];
 *  }
 * <PRE>
 * 
 * LineNumberTable_attribute structure contains the following items:
 * <PRE>
 *    line_number_table_length
 * </PRE>
 * The value of the line_number_table_length item indicates the number of
 * entries in the line_number_table array.
 * <PRE>
 *    line_number_table[]
 * </PRE>
 * Each entry in the line_number_table array indicates that the line number
 * in the original source file changes at a given point in the code array.<P>
 * 
 * Each line_number_table entry must contain the following two items:
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
final class LineNumberTableAttribute_info extends Attribute_info
{
    // public: ................................................................
    
    // ACCESSORS:
    
    /**
     * Returns {@link LineNumber_info} descriptor at a given offset.
     * 
     * @param offset line number entry offset [must be in [0, size()) range;
     * input not checked]
     * @return LineNumber_info descriptor [never null]
     * 
     * @throws IndexOutOfBoundsException if 'offset' is outside of valid range
     */
    public LineNumber_info get (final int offset)
    {
        return (LineNumber_info) m_lines.get (offset);
    }
    
    /**
     * Returns the number of descriptors in this collection [can be 0].
     */
    public int size ()
    {
        return m_lines.size ();
    }
    
    public long length ()
    {
        return 8 + (m_lines.size () << 2); // use size() if class becomes non-final
    }
    
    // Visitor:
    
    public void accept (final IAttributeVisitor visitor, final Object ctx)
    {
        visitor.visit (this, ctx);
    }
    
    public String toString ()
    {
        final StringBuffer s = new StringBuffer ("LineNumberTableAttribute_info: [attribute_name_index = " + m_name_index + ", attribute_length = " + length () + "]\n");

        for (int l = 0; l < size (); ++ l)
        {
            s.append ("            " + get (l));
            s.append ("\n"); // TODO: proper EOL const
        }
        
        return s.toString ();
    }
    
    // Cloneable:
    
    /**
     * Performs a deep copy.
     */
    public Object clone ()
    {
        final LineNumberTableAttribute_info _clone = (LineNumberTableAttribute_info) super.clone ();
        
        // do deep copy:
        final int lines_count = m_lines.size (); // use size() if class becomes non-final
        _clone.m_lines = new ArrayList (lines_count);
        for (int e = 0; e < lines_count; ++ e)
        {
            _clone.m_lines.add (((LineNumber_info) m_lines.get (e)).clone ());
        }
        
        return _clone;
    }

    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        super.writeInClassFormat (out);
        
        final int lines_count = m_lines.size (); // use size() if class becomes non-final
        out.writeU2 (lines_count);
        
        for (int l = 0; l < lines_count; ++ l)
        {
            ((LineNumber_info) m_lines.get (l)).writeInClassFormat (out);
        }
    }
    
    // protected: .............................................................

    // package: ...............................................................


    LineNumberTableAttribute_info (final int attribute_name_index, final long attribute_length,
                                   final UDataInputStream bytes)
        throws IOException
    {
        super (attribute_name_index, attribute_length);
        
        final int lines_count = bytes.readU2 ();
        m_lines = new ArrayList (lines_count);
        
        for (int i = 0; i < lines_count; i++)
        {
            m_lines.add (new LineNumber_info (bytes));
        }
    }
    
    // private: ...............................................................
    
    
    private List/* LineNumber_info */ m_lines; // never null

} // end of class
// ----------------------------------------------------------------------------