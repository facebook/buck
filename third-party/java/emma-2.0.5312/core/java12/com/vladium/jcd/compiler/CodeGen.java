/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: CodeGen.java,v 1.1.1.1 2004/05/09 16:57:49 vlad_r Exp $
 */
package com.vladium.jcd.compiler;

import com.vladium.jcd.cls.ClassDef;
import com.vladium.jcd.cls.constant.CONSTANT_Integer_info;
import com.vladium.jcd.opcodes.IOpcodes;
import com.vladium.util.ByteArrayOStream;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class CodeGen implements IOpcodes
{
    // public: ................................................................
    
    
    public static void load_local_object_var (final ByteArrayOStream out, final int index)
    {
        if (index <= 3)
        {
            out.write (_aload_0 + index); // aload_n
        }
        else if (index <= 0xFF)
        {
            out.write2 (_aload,
                        index);  // indexbyte
        }
        else
        {
            out.write4 (_wide,
                        _aload,
                        index >>> 8,    // indexbyte1
                        index);         // indexbyte2
        }
    }
    
    public static void store_local_object_var (final ByteArrayOStream out, final int index)
    {
        if (index <= 3)
        {
            out.write (_astore_0 + index); // astore_n
        }
        else if (index <= 0xFF)
        {
            out.write2 (_astore,
                        index);  // indexbyte
        }
        else
        {
            out.write4 (_wide,
                        _astore,
                        index >>> 8,    // indexbyte1
                        index);         // indexbyte2
        }
        
        // [stack -1]
    }
    
    public static void push_int_value (final ByteArrayOStream out, final ClassDef cls, final int value)
    {
        if ((-1 <= value) && (value <= 5))
        {
            out.write (_iconst_0 + value);
        }
        else if ((-128 <= value) && (value <= 127))
        {
            out.write2 (_bipush,
                        value); // byte1
        }
        else if ((-32768 <= value) && (value <= 32767))
        {
            out.write3 (_sipush,
                        value >>> 8,    // byte1
                        value);         // byte2
        }
        else // we have to create an Integer constant in the constant pool:
        {
            // TODO: check if it's already there
            final int index = cls.getConstants ().add (new CONSTANT_Integer_info (value));
            
            if (index <= 0xFF)
            {
                out.write2 (_ldc,
                            index);  // index
            }
            else // must use ldc_w
            {
                out.write3 (_ldc_w,
                            index >>> 8,  // indexbyte1
                            index);       // indexbyte2
            }
        }
        
        // [stack +1]
    }
    
    public static void push_constant_index (final ByteArrayOStream out, final int index)
    {
        if (index <= 0xFF)
        {
            out.write2 (_ldc,
                       index);  // indexbyte
        }
        else
        {
            out.write3 (_ldc_w,
                        index >>> 8,     // indexbyte1
                        index);          // indexbyte2
        }
        
        // [stack +1]
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private CodeGen () {} // prevent subclassing

} // end of class
// ----------------------------------------------------------------------------
