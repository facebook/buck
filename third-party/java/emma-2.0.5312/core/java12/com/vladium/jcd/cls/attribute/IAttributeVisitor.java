/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IAttributeVisitor.java,v 1.1.1.1 2004/05/09 16:57:48 vlad_r Exp $
 */
package com.vladium.jcd.cls.attribute;

// ----------------------------------------------------------------------------
/**
 * @author (C) 2001, Vlad Roubtsov
 */
public
interface IAttributeVisitor
{
    // public: ................................................................
    
    Object visit (GenericAttribute_info attribute, Object ctx);
    
    Object visit (CodeAttribute_info attribute, Object ctx);
    Object visit (ConstantValueAttribute_info attribute, Object ctx);
    Object visit (ExceptionsAttribute_info attribute, Object ctx);
    Object visit (LineNumberTableAttribute_info attribute, Object ctx);
    Object visit (SourceFileAttribute_info attribute, Object ctx);
    Object visit (SyntheticAttribute_info attribute, Object ctx);
    Object visit (BridgeAttribute_info attribute, Object ctx);
    Object visit (InnerClassesAttribute_info attribute, Object ctx);

} // end of interface
// ----------------------------------------------------------------------------
