/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IReportGenerator.java,v 1.1.1.1 2004/05/09 16:57:37 vlad_r Exp $
 */
package com.vladium.emma.report;

import com.vladium.util.IProperties;
import com.vladium.emma.EMMARuntimeException;
import com.vladium.emma.data.IMetaData;
import com.vladium.emma.data.ICoverageData;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface IReportGenerator
{
    // public: ................................................................
    
    String getType ();
    
    // TODO: make sure reporters are reusable
    
    void process (IMetaData mdata, ICoverageData cdata, SourcePathCache cache, IProperties parameters)
        throws EMMARuntimeException;
    
    void cleanup ();
        
} // end of interface
// ----------------------------------------------------------------------------