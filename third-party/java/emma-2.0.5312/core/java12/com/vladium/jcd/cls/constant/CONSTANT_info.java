/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: CONSTANT_info.java,v 1.1.1.1 2004/05/09 16:57:48 vlad_r Exp $
 */
package com.vladium.jcd.cls.constant;

import java.io.IOException;

import com.vladium.jcd.compiler.IClassFormatOutput;
import com.vladium.jcd.lib.UDataInputStream;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * An abstract base for all other CONSTANT_XXX_info structures. See $4.4 in VM
 * spec 1.0 for all such structure definitions.
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
abstract class CONSTANT_info implements Cloneable, IClassFormatOutput
{
    // public: ................................................................
    
    
    /**
     * Returns the tag byte for this CONSTANT type [this data is
     * static class data].
     */
    public abstract byte tag ();
    
    // Visitor:
    
    public abstract Object accept (ICONSTANTVisitor visitor, Object ctx); 
    
    public abstract String toString ();
    
    /**
     * Returns the number of constant pool index slots occupied by this
     * CONSTANT type. This implementation defaults to returning '1'.
     * 
     * @see CONSTANT_Long_info
     * @see CONSTANT_Long_info
     * 
     * @return int
     */
    public int width ()
    {
        return 1;
    }
    
    
    /**
     * Virtual constructor method for all CONSTANT_XXX_info structures.
     */
    public static CONSTANT_info new_CONSTANT_info (final UDataInputStream bytes)
        throws IOException
    {
        byte tag = bytes.readByte ();                                                                                   
        
        switch (tag)
        {
        case CONSTANT_Utf8_info.TAG:
            return new CONSTANT_Utf8_info (bytes);
            
        case CONSTANT_Integer_info.TAG:
            return new CONSTANT_Integer_info (bytes);
            
        case CONSTANT_Float_info.TAG:
            return new CONSTANT_Float_info (bytes);
            
        case CONSTANT_Long_info.TAG:
            return new CONSTANT_Long_info (bytes);
            
        case CONSTANT_Double_info.TAG:
            return new CONSTANT_Double_info (bytes);
        
            
        case CONSTANT_Class_info.TAG:
            return new CONSTANT_Class_info (bytes);
            
        case CONSTANT_String_info.TAG:
            return new CONSTANT_String_info (bytes);
            
            
        case CONSTANT_Fieldref_info.TAG:
            return new CONSTANT_Fieldref_info (bytes);
            
        case CONSTANT_Methodref_info.TAG:
            return new CONSTANT_Methodref_info (bytes);
            
        case CONSTANT_InterfaceMethodref_info.TAG:
            return new CONSTANT_InterfaceMethodref_info (bytes);
            
            
        case CONSTANT_NameAndType_info.TAG:
            return new CONSTANT_NameAndType_info (bytes);
            
        default: throw new IllegalStateException ("CONSTANT_info: invalid tag value [" + tag + ']');
                 
        } // end of switch
    }
    
    // Cloneable:
    
    /**
     * Chains to super.clone() and removes CloneNotSupportedException
     * from the method signature.
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
        out.writeByte (tag ());
    }
    
    public static String tagToString (final CONSTANT_info constant)
    {
        switch (constant.tag ())
        {
        case CONSTANT_Utf8_info.TAG:
            return "CONSTANT_Utf8";
            
        case CONSTANT_Integer_info.TAG:
            return "CONSTANT_Integer";
            
        case CONSTANT_Float_info.TAG:
            return "CONSTANT_Float";
            
        case CONSTANT_Long_info.TAG:
            return "CONSTANT_Long";
            
        case CONSTANT_Double_info.TAG:
            return "CONSTANT_Double";
        
            
        case CONSTANT_Class_info.TAG:
            return "CONSTANT_Class";
            
        case CONSTANT_String_info.TAG:
            return "CONSTANT_String";
            
            
        case CONSTANT_Fieldref_info.TAG:
            return "CONSTANT_Fieldref";
            
        case CONSTANT_Methodref_info.TAG:
            return "CONSTANT_Methodref";
            
        case CONSTANT_InterfaceMethodref_info.TAG:
            return "CONSTANT_InterfaceMethodref";
            
            
        case CONSTANT_NameAndType_info.TAG:
            return "CONSTANT_NameAndType";
            
        default: throw new IllegalStateException ("CONSTANT_info: invalid tag value [" + constant.tag () + ']');
                 
        } // end of switch
    }
    
    // protected: .............................................................

    /*
    protected static final byte CONSTANT_Utf8                       = 1;
    protected static final byte CONSTANT_Integer                    = 3;
    protected static final byte CONSTANT_Float                      = 4;
    protected static final byte CONSTANT_Long                       = 5;
    protected static final byte CONSTANT_Double                     = 6;
    protected static final byte CONSTANT_Class                      = 7;
    protected static final byte CONSTANT_String                     = 8;
    protected static final byte CONSTANT_Fieldref                   = 9;
    protected static final byte CONSTANT_Methodref                  = 10;
    protected static final byte CONSTANT_InterfaceMethodref         = 11;
    protected static final byte CONSTANT_NameAndType                = 12;
    */
    
    protected CONSTANT_info ()
    {
    }
    
    // package: ...............................................................

    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
