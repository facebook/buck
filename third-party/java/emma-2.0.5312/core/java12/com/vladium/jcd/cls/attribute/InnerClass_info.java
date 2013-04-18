/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: InnerClass_info.java,v 1.1.1.1 2004/05/09 16:57:48 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

import java.io.IOException;

import com.vladium.jcd.compiler.IClassFormatOutput;
import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class InnerClass_info implements Cloneable, IClassFormatOutput
{
    // public: ................................................................
    
    public int m_outer_class_index, m_inner_class_index;
    public int m_inner_name_index;
    public int m_inner_access_flags;
    
    
    public InnerClass_info (final int outer_class_index, final int inner_class_index,
                            final int inner_name_index, final int inner_access_flags) 
    {
        m_outer_class_index = outer_class_index;
        m_inner_class_index = inner_class_index;
        m_inner_name_index = inner_name_index;
        m_inner_access_flags = inner_access_flags;
    }
    
    
    public String toString ()
    {
        return "innerclass_info: [m_outer_class_index = " + m_outer_class_index + ", m_inner_class_index = " + m_inner_class_index +
            ", m_inner_name_index = " + m_inner_name_index + ", m_inner_access_flags = " + m_inner_access_flags + "]"; 
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
        out.writeU2 (m_inner_class_index);
        out.writeU2 (m_outer_class_index);
        out.writeU2 (m_inner_name_index);
        out.writeU2 (m_inner_access_flags);
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    
    InnerClass_info (final UDataInputStream bytes) throws IOException
    {
        m_inner_class_index = bytes.readU2 ();
        m_outer_class_index = bytes.readU2 ();
        m_inner_name_index = bytes.readU2 ();
        m_inner_access_flags = bytes.readU2 ();
    }
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------