/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: CONSTANT_Class_info.java,v 1.1.1.1 2004/05/09 16:57:48 vlad_r Exp $
 */
package com.vladium.jcd.cls.constant;

import java.io.IOException;

import com.vladium.jcd.cls.ClassDef;
import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * The CONSTANT_Class_info structure is used to represent a class or an interface.<P>
 * 
 * The value of the name_index item must be a valid index into the constant pool
 * table. The constant pool entry at that index must be a {@link CONSTANT_Utf8_info}
 * structure representing a valid fully qualified Java class name that has been
 * converted to the class file's internal form. 
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class CONSTANT_Class_info extends CONSTANT_info
{
    // public: ................................................................

    public static final byte TAG = 7;
    
    public int m_name_index;
    
    
    public CONSTANT_Class_info (final int name_index)
    {
        m_name_index = name_index;
    }

    
    public final byte tag ()
    {
        return TAG;
    }
    
    /**
     * Returns the JVM class name within the constant pool context of 'cls'
     * class definition.
     * 
     * @param cls class that contains this constant
     * @return class name [in JVM format]
     */
    public String getName (final ClassDef cls)
    {
        return ((CONSTANT_Utf8_info) cls.getConstants ().get (m_name_index)).m_value;
    }
    
    // Visitor:
    
    public Object accept (final ICONSTANTVisitor visitor, final Object ctx)
    {
        return visitor.visit (this, ctx);
    } 

    
    public String toString ()
    {
        return "CONSTANT_Class: [name_index = " + m_name_index + ']';
    }
    
    // Cloneable: inherited clone() is Ok
    
    // IClassFormatOutput:
    
    public void writeInClassFormat (final UDataOutputStream out) throws IOException
    {
        super.writeInClassFormat (out);
        
        out.writeU2 (m_name_index);
    }
    
    // protected: .............................................................

    
    protected CONSTANT_Class_info (final UDataInputStream bytes) throws IOException
    {
        m_name_index = bytes.readU2 ();
    }
    
    // package: ...............................................................

    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
