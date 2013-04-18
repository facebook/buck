/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ClassWriter.java,v 1.1.1.1 2004/05/09 16:57:49 vlad_r Exp $
 */
package com.vladium.jcd.compiler;

import java.io.IOException;
import java.io.OutputStream;

import com.vladium.jcd.cls.*;
import com.vladium.jcd.lib.UDataOutputStream;

// ----------------------------------------------------------------------------
/**
 * @author (C) 2001, Vlad Roubtsov
 */
public
abstract class ClassWriter
{
    // public: ................................................................
    

    public static void writeClassTable (final ClassDef classTable, final OutputStream out)
        throws IOException
    {
        classTable.writeInClassFormat (new UDataOutputStream (out));
    }
    
    // protected: .............................................................

    // package: ...............................................................

    // private: ...............................................................
    
} // end of class
// ----------------------------------------------------------------------------
