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
package org.acme.bestpublishing.actions;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.action.ParameterDefinitionImpl;
import org.alfresco.repo.action.executer.ActionExecuterAbstractBase;
import org.alfresco.service.cmr.action.Action;
import org.alfresco.service.cmr.action.ParameterDefinition;
import org.alfresco.service.cmr.dictionary.DataTypeDefinition;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.workflow.WorkflowInstance;
import org.alfresco.service.namespace.QName;
import org.apache.commons.lang.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Alfresco repo action that creates a new chapter folder for an ISBN
 *
 * @author martin.bergljung@marversolutions.org
 * @version 1.0
 */
public class CreateChapterAction extends ActionExecuterAbstractBase {
    private static final Logger LOG = LoggerFactory.getLogger(CreateChapterAction.class);

    /**
     * Repo action parameters
     */
    public static final String PARAM_CHAPTER_TITLE = "chapterTitle";
    public static final String PARAM_CHAPTER_NUMBER = "chapterNumber";

    /**
     * BOPP Services
     */
    private BoppUtilsService boppUtilsService;

    /**
     * Alfresco Services
     */
    private NodeService nodeService;
    private FileFolderService fileFolderService;
    private WorkflowUtilsService workflowUtilsService;

    /**
     * Spring DI
     */

    public void setBoppUtilsService(final BoppUtilsService boppUtilsService) {
        this.boppUtilsService = boppUtilsService;
    }

    public void setWorkflowUtilsService(WorkflowUtilsService workflowUtilsService) {
        this.workflowUtilsService = workflowUtilsService;
    }

    public void setNodeService(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    public void setFileFolderService(FileFolderService fileFolderService) {
        this.fileFolderService = fileFolderService;
    }

    /**
     * Repo Action Interface implementation
     */

    @Override
    protected void addParameterDefinitions(List<ParameterDefinition> paramList) {
        for (String s : new String[] {PARAM_CHAPTER_TITLE, PARAM_CHAPTER_NUMBER}) {
            paramList.add(new ParameterDefinitionImpl(s, DataTypeDefinition.TEXT, true, getParamDisplayLabel(s)));
        }
    }

    @Override
    protected void executeImpl(Action action, NodeRef actionedUponNodeRef) {
        if (nodeService.exists(actionedUponNodeRef) == true) {
            // Get the new chapter folder's number and what chapter title to set
            String newChapterTitle = (String) action.getParameterValue(PARAM_CHAPTER_TITLE);
            String chapterNumberString = (String)action.getParameterValue(PARAM_CHAPTER_NUMBER);
            if (!NumberUtils.isNumber(chapterNumberString)) {
                throw new AlfrescoRuntimeException("Provided chapter number is not a number: " + chapterNumberString);
            }
            int newChapterNumber = Integer.parseInt(chapterNumberString);
            if (newChapterNumber < 1) {
                // Set it to 1, so the new chapter is inserted as first
                newChapterNumber = 1;
            }

            // Make sure we got an ISBN folder to which we are adding the chapter folder
            NodeRef isbnFolderNodeRef = actionedUponNodeRef;
            Serializable nodeName = nodeService.getProperty(isbnFolderNodeRef, ContentModel.PROP_NAME);
            if (nodeName == null) {
                throw new AlfrescoRuntimeException("ISBN Folder node name is null");
            }
            String isbn = (String) nodeName;

            if (!boppUtilsService.isISBN(isbn)) {
                throw new AlfrescoRuntimeException("ISBN Folder node name is not and ISBN number");
            }

            // Get the existing chapter folders for the ISBN
            Map<ChapterFolderInfo, NodeRef> chapterFolders = boppUtilsService.getSortedChapterFolders(isbnFolderNodeRef);

            // Adjust existing chapter folders if we are inserting a new chapter (i.e. it is not added last)
            if (newChapterNumber > chapterFolders.size()) {
                // Make sure we are adding the new chapter right after the last existing chapter number
                newChapterNumber = chapterFolders.size() + 1;
            } else {
                // The new chapter should go in between existing chapters so we need to update chapter folder names
                // and numbers before adding the new chapter
                Map<ChapterFolderInfo, NodeRef> reverseChapters = ((TreeMap)chapterFolders).descendingMap();
                for (Map.Entry<ChapterFolderInfo, NodeRef> existingChapterFolder : reverseChapters.entrySet()) {
                    NodeRef existingChapterFolderNodeRef = existingChapterFolder.getValue();
                    int existingFolderChapterNumber = existingChapterFolder.getKey().getChapterNr();
                    int updatedChapterNumber = existingFolderChapterNumber + 1;
                    String updatedChapterFolderName = BoppConstants.CHAPTER_FOLDER_NAME_PREFIX + updatedChapterNumber;
                    nodeService.setProperty(existingChapterFolderNodeRef, ContentModel.PROP_NAME, updatedChapterFolderName);
                    nodeService.setProperty(existingChapterFolderNodeRef,
                            BoppContentModel.ChapterMetadataAspect.Prop.CHAPTER_NUMBER, updatedChapterNumber);

                    // Check if we are at the position where we should add the new chapter folder, if so break out of loop
                    // as we don't need to update chapter folders that are coming before the new chapter in order
                    if (existingFolderChapterNumber == newChapterNumber) {
                        break;
                    }
                }
            }

            // Update ISBN folder metadata status to Partial now when we add a chapter with missing metadata
            // And also the number of chapters that now is one more
            int currenNumberOfChapters = (Integer) nodeService.getProperty(
                    isbnFolderNodeRef, BoppContentModel.BookMetadataAspect.Prop.BOOK_NUMBER_OF_CHAPTERS);
            nodeService.setProperty(isbnFolderNodeRef,
                    BoppContentModel.BookMetadataAspect.Prop.BOOK_NUMBER_OF_CHAPTERS, currenNumberOfChapters + 1);
            nodeService.setProperty(isbnFolderNodeRef,
                    BoppContentModel.BookMetadataAspect.Prop.BOOK_METADATA_STATUS, BoppContentModel.BookMetadataStatus.PARTIAL.toString());

            // Setup the basic ISBN folder metadata, that will also be set on the new chapter folder
            String bookTitle = (String) nodeService.getProperty(isbnFolderNodeRef,
                    BoppContentModel.BookMetadataAspect.Prop.BOOK_TITLE);
            String bookSubTitle = (String) nodeService.getProperty(isbnFolderNodeRef,
                    BoppContentModel.BookMetadataAspect.Prop.BOOK_SUBTITLE);
            String bookSubject = (String) nodeService.getProperty(isbnFolderNodeRef,
                    BoppContentModel.BookMetadataAspect.Prop.BOOK_SUBJECT_NAME);
            Map<QName, Serializable> bookMetadataAspectProps = new HashMap<QName, Serializable>();
            bookMetadataAspectProps.put(BoppContentModel.BookMetadataAspect.Prop.ISBN, isbn);
            bookMetadataAspectProps.put(BoppContentModel.BookMetadataAspect.Prop.BOOK_TITLE, bookTitle);
            bookMetadataAspectProps.put(BoppContentModel.BookMetadataAspect.Prop.BOOK_SUBTITLE, bookSubTitle);
            bookMetadataAspectProps.put(BoppContentModel.BookMetadataAspect.Prop.BOOK_SUBJECT_NAME, bookSubject);

            // Now create the new chapter folder with basic chapter metadata
            String chapterFolderName = BoppConstants.CHAPTER_FOLDER_NAME_PREFIX + newChapterNumber;
            FileInfo chapterFileInfo = fileFolderService.create(isbnFolderNodeRef, chapterFolderName, BoppContentModel.ChapterFolderType.QNAME);
            LOG.debug("Created chapter folder /Company Home/{}/{}/{} [chapterNo={}][chapterTitle={}][metaStatus={}]",
                    new Object[]{RHO_FOLDER_NAME, isbn, chapterFileInfo.getName(), newChapterNumber, newChapterTitle,
                            BoppContentModel.ChapterMetadataStatus.MISSING.toString()});
            Map<QName, Serializable> chapterMetadataAspectProps = new HashMap<>();
            chapterMetadataAspectProps.put(BoppContentModel.ChapterMetadataAspect.Prop.CHAPTER_NUMBER, newChapterNumber);
            chapterMetadataAspectProps.put(BoppContentModel.ChapterMetadataAspect.Prop.CHAPTER_TITLE, newChapterTitle);
            chapterMetadataAspectProps.put(BoppContentModel.ChapterMetadataAspect.Prop.CHAPTER_METADATA_STATUS,
                    BoppContentModel.ChapterMetadataStatus.MISSING.toString());
            nodeService.addAspect(chapterFileInfo.getNodeRef(), BoppContentModel.BookMetadataAspect.QNAME, bookMetadataAspectProps);
            nodeService.addAspect(chapterFileInfo.getNodeRef(), BoppContentModel.ChapterMetadataAspect.QNAME, chapterMetadataAspectProps);


            // And finally setup workflow variable that indicates if metadata is complete or not,
            // should be set to false now
            WorkflowInstance workflowInstance = workflowUtilsService.getWorkflowInstanceForIsbn(
                    BoppWorkflowModel.BOPP_INGEST_AND_PUBLISH_WORKFLOW_NAME, isbn);
            if (workflowInstance != null) {
                // We got a workflow instance, so set the var
                boolean metadataComplete = false;
                workflowUtilsService.setProcessVariable(workflowInstance.getId(), VAR_METADATA_COMPLETE, metadataComplete);
                LOG.debug("Setting workflow variable [{}={}] {}", new Object[] { VAR_METADATA_COMPLETE, metadataComplete,
                        "[workflowInstanceId=" + workflowInstance.getId()+"]" });
            } else {
                // Workflow instance has completed for this ISBN, so nothing to do
            }
        } else {
            LOG.error("Cannot create chapter folder, ISBN node reference does not exist {}", actionedUponNodeRef);
        }
    }
}

