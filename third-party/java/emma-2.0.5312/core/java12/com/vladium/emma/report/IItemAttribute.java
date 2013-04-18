/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IItemAttribute.java,v 1.1.1.1.2.1 2004/07/10 19:08:50 vlad_r Exp $
 */
package com.vladium.emma.report;

import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.util.Comparator;

import com.vladium.util.asserts.$assert;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface IItemAttribute
{
    // public: ................................................................
    
    // TODO: this modeling (units is an independent axis) is not ideal, because
    // not all of the attributes have meaningful separation by unit type
    
    // note: the ordering is consistent with code in Factory:
    
    int ATTRIBUTE_NAME_ID               = 0;
    int ATTRIBUTE_CLASS_COVERAGE_ID     = 1;
    int ATTRIBUTE_METHOD_COVERAGE_ID    = 2;
    int ATTRIBUTE_BLOCK_COVERAGE_ID     = 3;
    int ATTRIBUTE_LINE_COVERAGE_ID      = 4;
    
    // note: the ordering is consistent with code in Factory and SrcFileItem:
    
    int UNITS_COUNT = 0;
    int UNITS_INSTR = 1;    
    
    Comparator /* IItem */ comparator ();
    String getName ();
    void format (IItem item, StringBuffer appendTo);
    boolean passes (IItem item, int criterion); // ideally, criteria should come from a double-dispatched API
    
    
    abstract class Factory
    {
        public static IItemAttribute getAttribute (final int attributeID, final int unitsID)
        {
            if ($assert.ENABLED) $assert.ASSERT (attributeID >= ATTRIBUTE_NAME_ID && attributeID <= ATTRIBUTE_LINE_COVERAGE_ID, "invalid attribute ID: " + attributeID);
            if ($assert.ENABLED) $assert.ASSERT (unitsID >= UNITS_COUNT && unitsID <= UNITS_INSTR, "invalid units ID: " + unitsID);
            
            return ATTRIBUTES [unitsID][attributeID];
        }
        
        public static IItemAttribute [] getAttributes (final int unitsID)
        {
            if ($assert.ENABLED) $assert.ASSERT (unitsID >= UNITS_COUNT && unitsID <= UNITS_INSTR, "invalid units ID: " + unitsID);
            
            return (IItemAttribute []) ATTRIBUTES [unitsID].clone ();
        }
        
        private static abstract class Attribute implements IItemAttribute
        {
            public String getName ()
            {
                return m_name;
            }
            
             protected Attribute (final String name)
            {
                if (name == null) throw new IllegalArgumentException ("null input: name");
                
                m_name = name;
            }

            
            private final String m_name;
            
        } // end of nested class
        
        
        private static final class NameAttribute extends Attribute
                                                 implements IItemAttribute
        {
            public Comparator comparator ()
            {
                return m_comparator;
            }  
    
            public void format (final IItem item, final StringBuffer appendTo)
            {
                appendTo.append (item.getName ());
            }
            
            public boolean passes (final IItem item, final int criterion)
            {
                return true; // names always pass [for now]
            }

            
            private static final class NameComparator implements Comparator
            {
                public int compare (final Object l, final Object g)
                {
                    final IItem il = (IItem) l;
                    final IItem ig = (IItem) g;
                                        
                    return il.getName ().compareTo (ig.getName ());
                }

            } // end of nested class
            
            NameAttribute (final String name)
            {
                super (name);
                
                m_comparator = new NameComparator ();
            }
            
            
            private final Comparator m_comparator;
            
        } // end of nested class
        
        
        private static final class FractionAttribute extends Attribute
                                                     implements IItemAttribute
        {
            public Comparator comparator ()
            {
                return m_comparator;
            }  
    
            public void format (final IItem item, final StringBuffer appendTo)
            {
                final int n = item.getAggregate (m_numeratorAggregateID);
                final double n_scaled = (double) n / m_scale;
                final int d = item.getAggregate (m_denominatorAggregateID);
                
                // d can be 0 legally: the compiler can generate classes with no methods in them [happens with synthetic classes, for example]
                //if ($assert.ENABLED) $assert.ASSERT (d > 0, "[attr ID = " + m_denominatorAggregateID + "] invalid denominator: " + d);

                final int appendToStart = appendTo.length ();

                if (d == 0)
                    m_format.format (1.0F, appendTo, m_fieldPosition);
                else
                    m_format.format (n_scaled / d, appendTo, m_fieldPosition);
                
                final int iLimit = Math.max (1, 5 - appendTo.length () + appendToStart);
                for (int i = 0; i < iLimit; ++ i) appendTo.append (' ');
                
                appendTo.append ('(');
                m_nFormat.format (n_scaled, appendTo, m_fieldPosition);
                appendTo.append ('/');
                appendTo.append (d);
                appendTo.append (')');
            }
            
            public boolean passes (final IItem item, final int criterion)
            {
                final int n = item.getAggregate (m_numeratorAggregateID);
                final int d = item.getAggregate (m_denominatorAggregateID);
                
                return ((double) n) * IItem.PRECISION >=  ((double) d) * m_scale * criterion; 
            }
            
            
            private final class FractionComparator implements Comparator
            {
                public int compare (final Object l, final Object g)
                {
                    final IItem il = (IItem) l;
                    final IItem ig = (IItem) g;
                    
                    final double nil = il.getAggregate (m_numeratorAggregateID);
                    final double dil = il.getAggregate (m_denominatorAggregateID);
                    
                    final double nig = ig.getAggregate (m_numeratorAggregateID);
                    final double dig = ig.getAggregate (m_denominatorAggregateID);
                    
                    final double diff = nil * dig - nig * dil; 

                    return diff > 0.0 ? +1 : (diff < 0.0 ? -1 : 0);
                }
                
            } // end of inner class
            
            
            FractionAttribute (final String name, final int numeratorAggregateID, final int denominatorAggregateID, final int scale, final int nFractionDigits)
            {
                super (name);
                
                if ($assert.ENABLED) $assert.ASSERT (scale != 0, "scale: " + scale);

                m_numeratorAggregateID = numeratorAggregateID;
                m_denominatorAggregateID = denominatorAggregateID; // ok to be zero
                m_scale = scale;
                
                m_format = (DecimalFormat) NumberFormat.getPercentInstance (); // TODO: locale
                m_fieldPosition = new FieldPosition (DecimalFormat.INTEGER_FIELD);
                
                // TODO: set this from a pattern property
                //m_format.setMinimumFractionDigits (1);
                m_format.setMaximumFractionDigits (0);
                //m_format.setDecimalSeparatorAlwaysShown (false);
                
                m_nFormat = (DecimalFormat) NumberFormat.getInstance (); // TODO: locale
                m_nFormat.setGroupingUsed (false);
                m_nFormat.setMaximumFractionDigits (nFractionDigits);
                
                m_comparator = new FractionComparator ();
            }
            
            
            final int m_numeratorAggregateID, m_denominatorAggregateID;
            
            private final int m_scale;
            private final DecimalFormat m_format, m_nFormat;
            private final FieldPosition m_fieldPosition;
            private final Comparator m_comparator; 
            
        } // end of nested class



        private Factory () {}
        
        private static final IItemAttribute [/* unit */][/* attributes */] ATTRIBUTES; // set in <clinit> 
        
        static
        {
            final IItemAttribute nameAttribute = new NameAttribute ("name");
            
            final IItemAttribute classCoverageAttribute = new FractionAttribute ("class, %", IItem.COVERAGE_CLASS_COUNT, IItem.TOTAL_CLASS_COUNT, 1, 0);
            final IItemAttribute methodCoverageAttribute = new FractionAttribute ("method, %", IItem.COVERAGE_METHOD_COUNT, IItem.TOTAL_METHOD_COUNT, 1, 0);
            
            ATTRIBUTES = new IItemAttribute [][]
            {
                /* count: */
                {
                    nameAttribute,
                    classCoverageAttribute,
                    methodCoverageAttribute,
                    new FractionAttribute ("block, %", IItem.COVERAGE_BLOCK_COUNT, IItem.TOTAL_BLOCK_COUNT, 1, 0),
                    new FractionAttribute ("line, %", IItem.COVERAGE_LINE_COUNT, IItem.TOTAL_LINE_COUNT, IItem.PRECISION, 1),
                },
                /* instr: */
                {
                    nameAttribute,
                    classCoverageAttribute,
                    methodCoverageAttribute,
                    new FractionAttribute ("block, %", IItem.COVERAGE_BLOCK_INSTR, IItem.TOTAL_BLOCK_INSTR, 1, 0),
                    new FractionAttribute ("line, %", IItem.COVERAGE_LINE_INSTR, IItem.TOTAL_LINE_COUNT, IItem.PRECISION, 1),
                },
            };
        }
        
    } // end of nested class

} // end of interface
// ----------------------------------------------------------------------------