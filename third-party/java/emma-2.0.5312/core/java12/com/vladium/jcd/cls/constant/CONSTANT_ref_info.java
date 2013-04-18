/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: CONSTANT_ref_info.java,v 1.1.1.1 2004/05/09 16:57:49 vlad_r Exp $
 */
package com.vladium.jcd.cls.constant;

import java.io.IOException;

import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * Abstract base for all CONSTANT_XXXref_info structures. They all have a constant
 * pool pointer to a {@link CONSTANT_Class_info} and {@link CONSTANT_NameAndType_info}
 * entries.<P>
 * 
 * The value of the class_index item must be a valid index into the constant pool
 * table. The constant pool entry at that index must be a {@link CONSTANT_Class_info}
 * structure representing the class or interface type that contains the declaration
 * of the field or method.<P>
 * 
 * The class_index item of a {@link CONSTANT_Fieldref_info} or a {@link CONSTANT_Methodref_info}
 * structure must be a class type, not an interface type. The class_index item of
 * a {@link CONSTANT_InterfaceMethodref_info} structure must be an interface type
 * that declares the given method.
 *
 * @see CONSTANT_Fieldref_info
 * @see CONSTANT_Methodref_info
 * @see CONSTANT_InterfaceMethodref_info
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
abstract class CONSTANT_ref_info extends CONSTANT_info
{
    // public: ................................................................

    
    public int m_class_index;
    public int m_name_and_type_index;
    
    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        super.writeInClassFormat (out);
        
        out.writeU2 (m_class_index);
        out.writeU2 (m_name_and_type_index);
    }
    
    // Cloneable: inherited clone() is Ok
    
    // protected: .............................................................

    
    protected CONSTANT_ref_info (final UDataInputStream bytes)
        throws IOException
    {
        m_class_index = bytes.readU2 ();
        m_name_and_type_index = bytes.readU2 ();
    }
    
    protected CONSTANT_ref_info (final int class_index, final int name_and_type_index)
    {
        m_class_index = class_index;
        m_name_and_type_index = name_and_type_index;
    }
    
    // package: ...............................................................

    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
