/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: SourceFileAttribute_info.java,v 1.1.1.1 2004/05/09 16:57:48 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

import java.io.IOException;

import com.vladium.jcd.cls.ClassDef;
import com.vladium.jcd.cls.constant.CONSTANT_Utf8_info;
import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class SourceFileAttribute_info extends Attribute_info
{
    // public: ................................................................
    
    
    public int m_sourcefile_index;
    
    
    public SourceFileAttribute_info (final int attribute_name_index)
    {
        super (attribute_name_index, 0);
    }
    
    
    public long length ()
    {
        return 8;
    }
    
    public CONSTANT_Utf8_info getSourceFile (final ClassDef cls)
    {
        return (CONSTANT_Utf8_info) cls.getConstants ().get (m_sourcefile_index);
    }  
    
    // Visitor:
    
    public void accept (final IAttributeVisitor visitor, final Object ctx)
    {
        visitor.visit (this, ctx);
    }
    
    public String toString ()
    {
        return "SourceFileAttribute_info: [attribute_name_index = " + m_name_index + ", attribute_length = " + m_attribute_length + ']';
    }
    
    // Cloneable:
    
    /**
     * Performs a deep copy.
     */
    public Object clone ()
    {        
        return super.clone ();    
    }
       
    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        super.writeInClassFormat (out);
        
        out.writeU2 (m_sourcefile_index);
    }
    
    // protected: .............................................................
    
    // package: ...............................................................


    SourceFileAttribute_info (final int attribute_name_index, final long attribute_length,
                              final UDataInputStream bytes)
        throws IOException
    {
        super (attribute_name_index, attribute_length);
        
        m_sourcefile_index = bytes.readU2 ();
    }
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------

