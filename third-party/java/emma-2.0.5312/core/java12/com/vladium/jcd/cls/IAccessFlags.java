/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IAccessFlags.java,v 1.1.1.1 2004/05/09 16:57:46 vlad_r Exp $
 */
package com.vladium.jcd.cls;

// ----------------------------------------------------------------------------
/**
 * @author (C) 2001, Vlad Roubtsov
 */
public
interface IAccessFlags
{
    // public: ................................................................

    static final int ACC_PUBLIC         = 0x0001;
    static final int ACC_PRIVATE        = 0x0002;
    static final int ACC_PROTECTED      = 0x0004;
    static final int ACC_STATIC         = 0x0008;
    static final int ACC_FINAL          = 0x0010;
    static final int ACC_SYNCHRONIZED   = 0x0020;
    static final int ACC_SUPER          = 0x0020; // same bit value as ACC_SYNCHRONIZED
    static final int ACC_VOLATILE       = 0x0040;
    static final int ACC_BRIDGE         = 0x0040; // same bit value as ACC_VOLATILE
    static final int ACC_TRANSIENT      = 0x0080;
    static final int ACC_NATIVE         = 0x0100;
    static final int ACC_INTERFACE      = 0x0200;
    static final int ACC_ABSTRACT       = 0x0400;
    
    static final int ALL_ACC [] = new int []
    {
        ACC_PUBLIC,
        ACC_PRIVATE,
        ACC_PROTECTED,
        ACC_STATIC,
        ACC_FINAL,
        ACC_SYNCHRONIZED,     
        ACC_VOLATILE,
        ACC_TRANSIENT,
        ACC_NATIVE,
        ACC_INTERFACE,
        ACC_ABSTRACT,
    };
    
    static final String ALL_ACC_NAMES [] = new String []
    {
        "public",
        "private",
        "protected",
        "static",
        "final",
        "synchronized",     
        "volatile",
        "transient",
        "native",
        "interface",
        "abstract",
    };
    
    void setAccessFlags (int flags);
    int getAccessFlags ();
    
} // end of interface
// ----------------------------------------------------------------------------
