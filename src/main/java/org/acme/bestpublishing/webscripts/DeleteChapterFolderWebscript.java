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

import org.acme.bestpublishing.constants.BestPubConstants;
import org.acme.bestpublishing.model.BestPubContentModel;
import org.acme.bestpublishing.model.BestPubMetadataFileModel;
import org.acme.bestpublishing.props.ChapterFolderProperties;
import org.acme.bestpublishing.services.AlfrescoRepoUtilsService;
import org.acme.bestpublishing.services.BestPubUtilsService;
import org.alfresco.model.ContentModel;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeRef;
import org.apache.commons.lang.StringUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.extensions.webscripts.*;

import java.io.IOException;
import java.util.Map;

/**
 * This web script deletes a chapter folder and then re-orders the other chapter folders accordingly.
 *
 * @author martin.bergljung@marversolutions.org
 * @version 1.0
 */
public class DeleteChapterFolderWebscript extends AbstractWebScript {
    private static final Logger LOG = LoggerFactory.getLogger(DeleteChapterFolderWebscript.class);

    /**
     * Web Script parameters/URL parameters
     */
    private final String PARAM_NODE_REF = "nodeRef";

    /**
     * Alfresco Services
     */
    private ServiceRegistry serviceRegistry;

    /**
     * BestPub Services
     */
    private AlfrescoRepoUtilsService alfrescoRepoUtilsService;
    private BestPubUtilsService bestPubUtilsService;

    /**
     * Spring DI
     */

    public void setServiceRegistry(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    public void setAlfrescoRepoUtilsService(AlfrescoRepoUtilsService alfrescoRepoUtilsService) {
        this.alfrescoRepoUtilsService = alfrescoRepoUtilsService;
    }

    public void setBestPubUtilsService(BestPubUtilsService bestPubUtilsService) {
        this.bestPubUtilsService = bestPubUtilsService;
    }

    /**
     * Web Script Interface implementation
     */

    @Override
    public void execute(final WebScriptRequest req, final WebScriptResponse res) throws IOException {
        String paramNodeRef = req.getParameter(PARAM_NODE_REF);
        if (StringUtils.isBlank(paramNodeRef)) {
            String msg = "NodeRef parameter for chapter folder is missing, cannot delete chapter folder";
            LOG.error(msg);
            throw new WebScriptException(msg);
        }

        JSONObject jsonResult = new JSONObject();
        NodeRef chapterFolder2DeleteNodeRef = new NodeRef(paramNodeRef);

        try {
            jsonResult.put("success", false);
            if (serviceRegistry.getNodeService().exists(chapterFolder2DeleteNodeRef) == true) {

                // Get the chapter number for the chapter folder we are deleting so we can use it in the loop below
                int deletedFolderChapterNumber = (Integer) serviceRegistry.getNodeService().getProperty(
                        chapterFolder2DeleteNodeRef, BestPubContentModel.ChapterInfoAspect.Prop.CHAPTER_NUMBER);

                // Get the parent ISBN folder node reference so we can get to the rest of the chapter folders
                NodeRef isbnFolderNodeRef = serviceRegistry.getNodeService().getPrimaryParent(
                        chapterFolder2DeleteNodeRef).getParentRef();

                // Now delete the chapter folder before we start adjusting the rest of the chapter folders
                serviceRegistry.getNodeService().deleteNode(chapterFolder2DeleteNodeRef);

                // Adjust existing chapter folders, unless we are deleting the last one.
                // Get the existing chapter folders for the ISBN.
                Map<ChapterFolderProperties, NodeRef> chapterFolders = bestPubUtilsService.getSortedChapterFolders(
                        isbnFolderNodeRef);
                for (Map.Entry<ChapterFolderProperties, NodeRef> existingChapterFolder : chapterFolders.entrySet()) {
                    NodeRef existingChapterFolderNodeRef = existingChapterFolder.getValue();
                    // First, make sure the node exists, could be the one we just deleted
                    if (serviceRegistry.getNodeService().exists(existingChapterFolderNodeRef) == true) {
                        Integer existingFolderChapterNumber = (Integer)existingChapterFolder.getKey().get(
                                BestPubMetadataFileModel.CHAPTER_METADATA_NUMBER_PROP_NAME);
                        if (existingFolderChapterNumber > deletedFolderChapterNumber) {
                            // This chapter folder comes after the one that was deleted,
                            // so adjust chapter folder name and chapter number
                            int updatedChapterNumber = existingFolderChapterNumber - 1;
                            String updatedChapterFolderName =
                                    bestPubUtilsService.getChapterFolderName(updatedChapterNumber);
                            serviceRegistry.getNodeService().setProperty(
                                    existingChapterFolderNodeRef, ContentModel.PROP_NAME, updatedChapterFolderName);
                            serviceRegistry.getNodeService().setProperty(existingChapterFolderNodeRef,
                                    BestPubContentModel.ChapterInfoAspect.Prop.CHAPTER_NUMBER, updatedChapterNumber);
                        }
                    }
                }

                // Update ISBN folder metadata and set the number of chapters to one less
                int currenNumberOfChapters = (Integer) serviceRegistry.getNodeService().getProperty(
                        isbnFolderNodeRef, BestPubContentModel.BookInfoAspect.Prop.BOOK_NUMBER_OF_CHAPTERS);
                serviceRegistry.getNodeService().setProperty(isbnFolderNodeRef,
                        BestPubContentModel.BookInfoAspect.Prop.BOOK_NUMBER_OF_CHAPTERS, currenNumberOfChapters - 1);

                jsonResult.put("success", true);
                LOG.debug("Deleted chapter folder successfully [chapFolderNodeRef={}][isbnNodeRef={}]",
                        chapterFolder2DeleteNodeRef, isbnFolderNodeRef);
            } else {
                String msg = "Cannot delete chapter folder, its node reference does not exist " + chapterFolder2DeleteNodeRef;
                LOG.error(msg);
                throw new WebScriptException(Status.STATUS_INTERNAL_SERVER_ERROR, msg);
            }

            alfrescoRepoUtilsService.writeJsonResponse(res, jsonResult.toString());
        } catch (Exception e) {
            throw new WebScriptException(Status.STATUS_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
