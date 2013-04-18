/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IReportDataModel.java,v 1.1.1.1 2004/05/09 16:57:37 vlad_r Exp $
 */
package com.vladium.emma.report;

import com.vladium.emma.data.ICoverageData;
import com.vladium.emma.data.IMetaData;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface IReportDataModel
{
    // public: ................................................................
    
    IReportDataView getView (int viewType);
    
    abstract class Factory
    {
        /**
         * This operation merely stores mdata and cdata references, it does not
         * perform any data processing until getView() is actually called.
         */
        public static IReportDataModel create (final IMetaData mdata, final ICoverageData cdata)
        {
            return new ReportDataModel (mdata, cdata);
        }
        
    } // end of nested class

} // end of interface
// ----------------------------------------------------------------------------