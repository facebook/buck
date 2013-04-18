/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ISessionData.java,v 1.1.1.1 2004/05/09 16:57:32 vlad_r Exp $
 */
package com.vladium.emma.data;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface ISessionData
{
    // public: ................................................................
    
    IMetaData getMetaData ();
    ICoverageData getCoverageData ();

} // end of interface
// ----------------------------------------------------------------------------