/*
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package org.acme.bestpublishing.webscripts;

import org.acme.bestpublishing.model.BestPubContentModel;
import org.acme.bestpublishing.services.BestPubUtilsService;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeRef;
import org.apache.commons.lang.StringUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.extensions.webscripts.*;

import java.io.IOException;
import java.io.Serializable;
import java.util.Date;

/**
 * The webscript compare the isbn book folder modified date with the published date.
 * If the modified date is after published date then the metadata has been
 * updated since the last published date.
 *
 * @author martin.bergljung@marversolutions.org
 * @version 1.0
 */
public class CheckMetadataUpdatesWebscript extends AbstractWebScript {
    private static final Logger LOG = LoggerFactory.getLogger(CheckMetadataUpdatesWebscript.class);

    /**
     *  Parameter used to pass in ISBN Folder node reference
     */
    private final String PARAM_NODE_REF = "nodeRef";

    /**
     * Alfresco Services
     */
    private ServiceRegistry serviceRegistry;

    /**
     * Best Publishing Services
     */
    private BestPubUtilsService bestPubUtilsService;


    /**
     * Spring DI
     */

    public void setBestPubUtilsService(final BestPubUtilsService bestPubUtilsService) {
        this.bestPubUtilsService = bestPubUtilsService;
    }

    public void setServiceRegistry(final ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    /**
     * Interface Implementation
     */

    @Override
    public void execute(final WebScriptRequest req, final WebScriptResponse res) throws IOException {
        String paramNodeRef = req.getParameter(PARAM_NODE_REF);

        if (StringUtils.isBlank(paramNodeRef)) {
            String msg = "The 'nodeRef' parameter is null.";
            LOG.error(msg);
            throw new WebScriptException(msg);
        }

        JSONObject jsonResult = new JSONObject();
        NodeRef isbnNodeRef = new NodeRef(paramNodeRef);

        try {
            jsonResult.put("success", false);
            jsonResult.put("isMetadataUpdated", false);
            Serializable propVal = serviceRegistry.getNodeService().getProperty(isbnNodeRef,
                    BestPubContentModel.WebPublishingInfoAspect.Prop.WEB_PUBLISHED_DATE);
            if (propVal != null) {
                // Book has been published before, check if anything has been updated since publishing date?
                Date publishedDate = (Date) propVal;
                jsonResult.put("isMetadataUpdated", (
                        bestPubUtilsService.checkModifiedDates(isbnNodeRef, publishedDate) == null ? false : true));
            } else {
                // Book has not been published yet, so set metadata as being updated so we can allow publishing
                jsonResult.put("isMetadataUpdated", true);
            }
            jsonResult.put("success", true);
            res.setContentType("application/json");
            res.getWriter().append(jsonResult.toString());
        } catch (Exception e) {
            throw new WebScriptException(Status.STATUS_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
