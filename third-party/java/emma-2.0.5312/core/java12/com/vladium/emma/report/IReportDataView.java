/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IReportDataView.java,v 1.1.1.1 2004/05/09 16:57:37 vlad_r Exp $
 */
package com.vladium.emma.report;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public interface IReportDataView
{
    // public: ................................................................
    

    int HIER_CLS_VIEW   = 0;
    int HIER_SRC_VIEW   = 1;   
    
    IItem getRoot ();

} // end of interface
// ----------------------------------------------------------------------------