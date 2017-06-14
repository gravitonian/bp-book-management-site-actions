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

import org.acme.bestpublishing.actions.PublishBookAction;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.action.Action;
import org.alfresco.service.cmr.repository.NodeRef;
import org.apache.commons.lang.StringUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.extensions.webscripts.*;

import java.io.IOException;

/**
 * This Web Script will publish the Book as an EPub file.
 * 
 * @author martin.bergljung@marversolutions.org
 * @version 1.0
 */
public class PublishBookWebscript extends AbstractWebScript {
    private static final Logger LOG = LoggerFactory.getLogger(PublishBookWebscript.class);

    /**
     * Web Script parameters
     */
    private final String PARAM_NODE_REF = "nodeRef";
    private final String PARAM_BOOK_ISBN = "bookIsbn";

    /**
     * Alfresco Services
     */
    private ServiceRegistry serviceRegistry;

    /**
     * Spring DI
     */

    public void setServiceRegistry(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    /**
     * Web Script Interface implementation
     */

    @Override
    public void execute(final WebScriptRequest req, final WebScriptResponse res) throws IOException {
        String paramNodeRef = req.getParameter(PARAM_NODE_REF);
        String paramBookIsbn = req.getParameter(PARAM_BOOK_ISBN);

        if (StringUtils.isBlank(paramNodeRef)) {
            String msg = "The 'nodeRef' parameter is null.";
            LOG.error(msg);
            throw new WebScriptException(msg);
        }

        try {
            // Trigger the publishing action, shows invocation of a Repo Action from Java code
            // And shows how to invoke an action async.
            Action publishBookToWebAction = serviceRegistry.getActionService().createAction(PublishBookAction.NAME);
            publishBookToWebAction.setExecuteAsynchronously(false);
            boolean executeAsynchronoulsy = true;
            boolean checkConditions = false;
            NodeRef nodeRef = new NodeRef(paramNodeRef);
            serviceRegistry.getActionService().executeAction(
                    publishBookToWebAction, nodeRef, checkConditions, executeAsynchronoulsy);

            JSONObject jsonResult = new JSONObject();
            jsonResult.put("isbn", paramBookIsbn);
            jsonResult.put("publishingInitiated", true);
            res.setContentType("application/json");
            res.getWriter().append(jsonResult.toString());
        } catch (Exception e) {
            throw new WebScriptException(Status.STATUS_INTERNAL_SERVER_ERROR, e.getMessage());
        }

        LOG.debug("The content for Book [{}] has been manually published with success", paramBookIsbn);
    }
}
