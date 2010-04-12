/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.analysis.controls;

import org.dom4j.Attribute;
import org.dom4j.Element;
import org.dom4j.QName;
import org.dom4j.Text;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.util.PropertyContext;
import org.orbeon.oxf.util.XPathCache;
import org.orbeon.oxf.xforms.*;
import org.orbeon.oxf.xforms.analysis.XPathAnalysis;
import org.orbeon.oxf.xforms.analysis.model.Model;
import org.orbeon.oxf.xforms.xbl.XBLBindings;
import org.orbeon.oxf.xml.dom4j.Dom4jUtils;
import org.orbeon.oxf.xml.dom4j.ExtendedLocationData;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.saxon.dom4j.DocumentWrapper;
import org.orbeon.saxon.expr.Expression;
import org.orbeon.saxon.om.NodeInfo;

import java.util.*;

/**
 * Hold the static analysis for an XForms control.
 */
public class ControlAnalysis {

    public final XFormsStaticState staticState;

    public final XBLBindings.Scope scope;
    public final String prefixedId;
    public final Element element;
    public final LocationData locationData;
    public final int index;
    public final boolean hasNodeBinding;
    public final boolean isValueControl;

    public final ContainerAnalysis parentControlAnalysis;
    public final Map<String, ControlAnalysis> inScopeVariables; // variable name -> ControlAnalysis
    
    private final LHHAAnalysis nestedLabel;
    private final LHHAAnalysis nestedHelp;
    private final LHHAAnalysis nestedHint;
    private final LHHAAnalysis nestedAlert;

    private LHHAAnalysis externalLabel;
    private LHHAAnalysis externalHelp;
    private LHHAAnalysis externalHint;
    private LHHAAnalysis externalAlert;

    public final String modelPrefixedId;

    public final XPathAnalysis bindingAnalysis;
    public final XPathAnalysis valueAnalysis;

    private String classes;

    public class LHHAAnalysis {
        public final Element element;
        public final boolean isLocal;
        public final boolean hasStaticValue;

        public final XPathAnalysis valueAnalysis;

        public LHHAAnalysis(PropertyContext propertyContext, DocumentWrapper controlsDocumentInfo, Element element, boolean isLocal) {
            this.element = element;
            this.isLocal = isLocal;

            this.hasStaticValue = hasStaticValue(propertyContext, controlsDocumentInfo);
            // TODO: model change

            if (!this.hasStaticValue && staticState.isXPathAnalysis()) {
                final String lhhaPrefixedId = XFormsUtils.getRelatedEffectiveId(prefixedId, XFormsUtils.getElementStaticId(element));
                if (element.attribute("value") != null || element.attribute("ref") != null) {
                    // 1. E.g. <xforms:label model="..." context="..." value|ref="..."/>
                    final XPathAnalysis bindingAnalysis = computeBindingAnalysis(element);
                    this.valueAnalysis = analyzeValueXPath(bindingAnalysis, element, lhhaPrefixedId);
                } else {
                    // 2. E.g. <xforms:label>...<xforms:output value|ref=""/>...</xforms:label>

                    // TODO: handle model/context on enclosing LHHA element

                    final Set<XPathAnalysis> analyses = new LinkedHashSet<XPathAnalysis>();

                    final boolean hostLanguageAVTs = XFormsProperties.isHostLanguageAVTs();
                    Dom4jUtils.visitSubtree(element, new Dom4jUtils.VisitorListener() {
                        public void startElement(Element element) {
                            if (element.getQName().equals(XFormsConstants.XFORMS_OUTPUT_QNAME)) {
                                // Add dependencies
                                final XPathAnalysis bindingAnalysis = computeBindingAnalysis(element);
                                analyses.add(analyzeValueXPath(bindingAnalysis, element, lhhaPrefixedId));
                            } else {
                                final List<Attribute> attributes = element.attributes();
                                if (attributes.size() > 0) {
                                    for (final Attribute currentAttribute: attributes) {
                                        final String currentAttributeValue = currentAttribute.getValue();

                                        if (hostLanguageAVTs && currentAttributeValue.indexOf('{') != -1) {
                                            // TODO: check for AVTs
                                            analyses.add(null);
                                        }
                                    }
                                }
                            }
                        }

                        public void endElement(Element element) {}
                        public void text(Text text) {}
                    });

                    if (analyses.size() > 0) {
                        final Iterator<XPathAnalysis> i = analyses.iterator();
                        XPathAnalysis combinedAnalysis = i.next();
                        if (combinedAnalysis != null && combinedAnalysis.figuredOutDependencies) {
                            while (i.hasNext()) {
                                final XPathAnalysis nextAnalysis = i.next();
                                if (nextAnalysis != null && nextAnalysis.figuredOutDependencies) {
                                    combinedAnalysis.combine(nextAnalysis);
                                } else {
                                    combinedAnalysis = null;
                                    break;
                                }
                            }

                            this.valueAnalysis = combinedAnalysis;
                        } else {
                            this.valueAnalysis = null;
                        }

                    } else {
                        this.valueAnalysis = null;
                    }
                }
            } else {
                // Value of LHHA is 100% static
                this.valueAnalysis = null;
//                XFormsUtils.getStaticChildElementValue(element, true, null);
            }
        }

        private boolean hasStaticValue(PropertyContext propertyContext, DocumentWrapper controlsDocumentInfo) {

            // Gather itemset information
            final NodeInfo lhhaNodeInfo = controlsDocumentInfo.wrap(element);

            // Try to figure out if we have a dynamic LHHA element. This attempts to cover all cases, including nested
            // xforms:output controls. Also check for AVTs ion @class and @style.
            return (Boolean) XPathCache.evaluateSingle(propertyContext, lhhaNodeInfo,
                    "exists(descendant-or-self::xforms:*[@ref or @nodeset or @bind or @value or (@class, @style)[contains(., '{')]])",
                    XFormsStaticState.BASIC_NAMESPACE_MAPPINGS, null, null, null, null, getLocationData());
        }

        public LocationData getLocationData() {
            return new ExtendedLocationData((LocationData) element.getData(), "gathering static control information", element);
        }
    }

    public ControlAnalysis(PropertyContext propertyContext, XFormsStaticState staticState, DocumentWrapper controlsDocumentInfo,
                           XBLBindings.Scope scope, String prefixedId, Element element, LocationData locationData, int index,
                           boolean isValueControl, ContainerAnalysis parentControlAnalysis,
                           Map<String, ControlAnalysis> inScopeVariables) {

        this.staticState = staticState;
        this.scope = scope;
        this.prefixedId = prefixedId;
        this.element = element;
        this.locationData = locationData;
        this.index = index;
        this.isValueControl = isValueControl;
        this.parentControlAnalysis = parentControlAnalysis;
        this.inScopeVariables = inScopeVariables;

        if (element != null) {
            final boolean hasBind = element.attribute("bind") != null;
            final boolean hasRef = element.attribute("ref") != null;
            final boolean hasNodeset = element.attribute("nodeset") != null;

            // TODO: check for mandatory bindings
//            if (XFormsConstants.XFORMS_NAMESPACE_URI.equals(controlURI)) {
//                        if (XFormsControlFactory.MANDATORY_SINGLE_NODE_CONTROLS.get(controlName) != null && !(hasRef || hasBind)) {
//                            throw new ValidationException("Missing mandatory single node binding for element: " + controlElement.getQualifiedName(), locationData);
//                        }
//                        if (XFormsControlFactory.NO_SINGLE_NODE_CONTROLS.get(controlName) != null && (hasRef || hasBind)) {
//                            throw new ValidationException("Single node binding is prohibited for element: " + controlElement.getQualifiedName(), locationData);
//                        }
//                        if (XFormsControlFactory.MANDATORY_NODESET_CONTROLS.get(controlName) != null && !(hasRef || hasNodeset || hasBind)) {
//                            throw new ValidationException("Missing mandatory nodeset binding for element: " + controlElement.getQualifiedName(), locationData);
//                        }
//                        if (XFormsControlFactory.NO_NODESET_CONTROLS.get(controlName) != null && hasNodeset) {
//                            throw new ValidationException("Node-set binding is prohibited for element: " + controlElement.getQualifiedName(), locationData);
//                        }
//                        if (XFormsControlFactory.SINGLE_NODE_OR_VALUE_CONTROLS.get(controlName) != null && !(hasRef || hasBind || controlElement.attribute("value") != null)) {
//                            throw new ValidationException("Missing mandatory single node binding or value attribute for element: " + controlElement.getQualifiedName(), locationData);
//                        }
//            }

            this.hasNodeBinding = hasBind || hasRef || hasNodeset;
        } else {
            this.hasNodeBinding = false;
        }

        this.modelPrefixedId = computeModelPrefixedId();

        if (staticState.isXPathAnalysis()) {
            this.bindingAnalysis = computeBindingAnalysis(element);
            this.valueAnalysis = computeValueAnalysis();
        } else {
            this.bindingAnalysis = null;
            this.valueAnalysis = null;
        }

        this.nestedLabel = findNestedLHHA(propertyContext, controlsDocumentInfo, XFormsConstants.LABEL_QNAME);
        this.nestedHelp = findNestedLHHA(propertyContext, controlsDocumentInfo, XFormsConstants.HELP_QNAME);
        this.nestedHint = findNestedLHHA(propertyContext, controlsDocumentInfo, XFormsConstants.HINT_QNAME);
        this.nestedAlert = findNestedLHHA(propertyContext, controlsDocumentInfo, XFormsConstants.ALERT_QNAME);
    }

    private LHHAAnalysis findNestedLHHA(PropertyContext propertyContext, DocumentWrapper controlsDocumentInfo, QName qName) {
        final Element e = findNestedLHHAElement(propertyContext, controlsDocumentInfo, qName);
        return (e != null) ? new LHHAAnalysis(propertyContext, controlsDocumentInfo, e, true) : null;
    }

    protected Element findNestedLHHAElement(PropertyContext propertyContext, DocumentWrapper controlsDocumentInfo, QName qName) {
        return element.element(qName);
    }

    public void setExternalLHHA(PropertyContext propertyContext, DocumentWrapper controlsDocumentInfo, Element lhhaElement) {
        assert lhhaElement != null;
        final String name = lhhaElement.getName();
        if (XFormsConstants.LABEL_QNAME.getName().equals(name)) {
            externalLabel = new LHHAAnalysis(propertyContext, controlsDocumentInfo, lhhaElement, false);
        } else if (XFormsConstants.HELP_QNAME.getName().equals(name)) {
            externalHelp = new LHHAAnalysis(propertyContext, controlsDocumentInfo, lhhaElement, false);
        } else if (XFormsConstants.HINT_QNAME.getName().equals(name)) {
            externalHint = new LHHAAnalysis(propertyContext, controlsDocumentInfo, lhhaElement, false);
        } else if (XFormsConstants.ALERT_QNAME.getName().equals(name)) {
            externalAlert = new LHHAAnalysis(propertyContext, controlsDocumentInfo, lhhaElement, false);
        }
    }

    public LHHAAnalysis getLabel() {
        return (nestedLabel != null) ? nestedLabel : externalLabel;
    }

    public LHHAAnalysis getHelp() {
        return (nestedHelp != null) ? nestedHelp : externalHelp;
    }

    public LHHAAnalysis getHint() {
        return (nestedHint != null) ? nestedHint : externalHint;
    }

    public LHHAAnalysis getAlert() {
        return (nestedAlert != null) ? nestedAlert : externalAlert;
    }

    protected String computeModelPrefixedId() {
        if (element != null) {
            final String localModelId = element.attributeValue("model");
            if (localModelId != null) {
                // Get model prefixed id and verify it belongs to this scope
                final String localModelPrefixedId = scope.getPrefixedIdForStaticId(localModelId);
                if (staticState.getModel(localModelPrefixedId) == null)
                    throw new ValidationException("Reference to non-existing model id: " + localModelId, locationData);
                return localModelPrefixedId;
            } else {
                final ControlAnalysis ancestor = getAncestorControlAnalysisInScope();
                if (ancestor != null) {
                    // There is an ancestor control in the same scope, use its model id
                    return ancestor.modelPrefixedId;
                } else {
                    // Top-level control in a new scope, use default model id for scope
                    return staticState.getDefaultModelPrefixedIdForScope(scope);
                }
            }
        } else {
            return null;
        }
    }

    protected XPathAnalysis computeBindingAnalysis(Element element) {
        if (element != null) {
            final String bindingExpression;
            if (element.attributeValue("context") == null) {
                final String ref = element.attributeValue("ref");
                if (ref != null) {
                    bindingExpression = ref;
                } else {
                    bindingExpression = element.attributeValue("nodeset");
                }
            } else {
                // TODO: handle @context
                bindingExpression = null;
            }

            final XPathAnalysis baseAnalysis = findOrCreateBaseAnalysis();
            if ((bindingExpression != null)) {
                // New binding expression
                return analyzeXPath(staticState, baseAnalysis, prefixedId, bindingExpression);
            } else {
                // TODO: TEMP: just do this for now so that controls w/o their own binding also get binding updated
                return baseAnalysis;
            }
        } else {
            return null;
        }
    }

    protected XPathAnalysis computeValueAnalysis () {
        if (element != null) {
            final XPathAnalysis baseAnalysis = findOrCreateBaseAnalysis();
            if (isValueControl) {
                final boolean isXXFormsAttribute = element.getQName().equals(XFormsConstants.XXFORMS_ATTRIBUTE_QNAME);
                if (isXXFormsAttribute) {
                    // TODO
                    // NOTE: bad design that AVT has @value as attribute name
                    return null;
                } else {
                    // Regular value control
                    return analyzeValueXPath(baseAnalysis, element, prefixedId);
                }
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    protected XPathAnalysis findOrCreateBaseAnalysis() {
        final XPathAnalysis baseAnalysis;
        final XPathAnalysis ancestor = getAncestorOrSelfBindingAnalysis();
        if (ancestor != null) {
            // There is an ancestor control in the same scope with same model, use its analysis as base
            baseAnalysis = ancestor;
        } else {
            // We are a top-level control in a scope/model combination, create analysis
            if (modelPrefixedId != null) {
                final Model model = staticState.getModel(modelPrefixedId);
                if (model.defaultInstancePrefixedId != null) {
                    // Start with instance('defaultInstanceId')
                    baseAnalysis = analyzeXPath(staticState, null, prefixedId, XPathAnalysis.buildInstanceString(model.defaultInstancePrefixedId));
                } else {
                    // No instance
                    baseAnalysis = null;
                }
            } else {
                // No model
                baseAnalysis = null;
            }
        }
        return baseAnalysis;
    }

    private ControlAnalysis getAncestorControlAnalysisInScope() {
        ControlAnalysis currentControlAnalysis = parentControlAnalysis;
        while (currentControlAnalysis != null) {

            if (currentControlAnalysis.scope.equals(scope)) {
                return currentControlAnalysis;
            }

            currentControlAnalysis = currentControlAnalysis.parentControlAnalysis;
        }

        return null;
    }

    private XPathAnalysis getAncestorOrSelfBindingAnalysis() {
        ControlAnalysis currentControlAnalysis = this;
        while (currentControlAnalysis != null) {

            if (currentControlAnalysis.bindingAnalysis != null
                    && currentControlAnalysis.modelPrefixedId.equals(modelPrefixedId)
                    && currentControlAnalysis.scope.equals(scope)) {
                return currentControlAnalysis.bindingAnalysis;
            }

            currentControlAnalysis = currentControlAnalysis.parentControlAnalysis;
        }

        return null;
    }

    private XPathAnalysis analyzeValueXPath(XPathAnalysis baseAnalysis, Element element, String prefixedId) {
            final String valueAttribute = element.attributeValue("value");
            if (valueAttribute != null) {
                // E.g. xforms:output/@value
                return analyzeXPath(staticState, baseAnalysis, prefixedId, valueAttribute);
            } else {
                // Value is considered the string value
                return analyzeXPath(staticState, baseAnalysis, prefixedId, "string()");
            }
        }

    protected XPathAnalysis analyzeXPath(XFormsStaticState staticState, XPathAnalysis baseAnalysis, String prefixedId, String xpathString) {
        // Create new expression
        // TODO: get expression from pool and pass in-scope variables (probably more efficient)
        final Expression expression = XPathCache.createExpression(staticState.getXPathConfiguration(), xpathString,
                staticState.getMetadata().namespaceMappings.get(prefixedId), XFormsContainingDocument.getFunctionLibrary());
        // Analyse it
        return new XPathAnalysis(staticState, expression, xpathString, baseAnalysis, inScopeVariables, scope, modelPrefixedId);

    }

    public void addClasses(String classes) {
        if (this.classes == null) {
            // Set
            this.classes = classes;
        } else {
            // Append
            this.classes = this.classes + ' ' + classes;
        }
    }

    public String getClasses() {
        return classes;
    }

    public int getLevel() {
        if (parentControlAnalysis == null)
            return 0;
        else
            return parentControlAnalysis.getLevel() + 1;
    }

    public RepeatAnalysis getAncestorRepeat() {
        ContainerAnalysis currentParent = parentControlAnalysis;
        while (currentParent != null) {
            if (currentParent instanceof RepeatAnalysis)
                return (RepeatAnalysis) currentParent;
            currentParent = currentParent.parentControlAnalysis;
        }
        return null;
    }
}