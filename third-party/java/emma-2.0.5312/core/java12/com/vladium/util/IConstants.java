/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IConstants.java,v 1.1.1.1 2004/05/09 16:57:52 vlad_r Exp $
 */
package com.vladium.util;

import java.io.File;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface IConstants
{
    // public: ................................................................
    
    String [] EMPTY_STRING_ARRAY = new String [0];
    File [] EMPTY_FILE_ARRAY = new File [0];
    int [] EMPTY_INT_ARRAY = new int [0];
    
    String EOL = System.getProperty ("line.separator", "\n");
    
    String INDENT_INCREMENT = "  ";

} // end of interface
// ----------------------------------------------------------------------------