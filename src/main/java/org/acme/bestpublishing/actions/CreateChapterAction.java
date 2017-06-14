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

import org.acme.bestpublishing.model.BestPubContentModel;
import org.acme.bestpublishing.model.BestPubMetadataFileModel;
import org.acme.bestpublishing.props.ChapterFolderProperties;
import org.acme.bestpublishing.services.AlfrescoRepoUtilsService;
import org.acme.bestpublishing.services.BestPubUtilsService;
import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.action.ParameterDefinitionImpl;
import org.alfresco.repo.action.executer.ActionExecuterAbstractBase;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.action.Action;
import org.alfresco.service.cmr.action.ParameterDefinition;
import org.alfresco.service.cmr.dictionary.DataTypeDefinition;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.namespace.QName;
import org.apache.commons.lang.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;

import static org.acme.bestpublishing.model.BestPubContentModel.*;

/**
 * Alfresco repo action that creates a new chapter folder for an ISBN,
 * re-ordering existing chapter folders if necessary.
 *
 * @author martin.bergljung@marversolutions.org
 * @version 1.0
 */
public class CreateChapterAction extends ActionExecuterAbstractBase {
    private static final Logger LOG = LoggerFactory.getLogger(CreateChapterAction.class);

    /**
     * Repo action parameters
     */
    public static final String PARAM_CHAPTER_NUMBER = "chapterNumber";
    public static final String PARAM_CHAPTER_TITLE = "chapterTitle";
    public static final String PARAM_CHAPTER_AUTHOR = "chapterAuthor";

    /**
     * BestPub Services
     */
    private BestPubUtilsService bestPubUtilsService;
    private AlfrescoRepoUtilsService alfrescoRepoUtilsService;

    /**
     * Alfresco Services
     */
    ServiceRegistry serviceRegistry;

    /**
     * Spring DI
     */

    public void setBestPubUtilsService(final BestPubUtilsService bestPubUtilsService) {
        this.bestPubUtilsService = bestPubUtilsService;
    }

    public void setAlfrescoRepoUtilsService(AlfrescoRepoUtilsService alfrescoRepoUtilsService) {
        this.alfrescoRepoUtilsService = alfrescoRepoUtilsService;
    }

    public void setServiceRegistry(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    /**
     * Repo Action Interface implementation
     */

    @Override
    protected void addParameterDefinitions(List<ParameterDefinition> paramList) {
        for (String param : new String[] {PARAM_CHAPTER_NUMBER, PARAM_CHAPTER_TITLE, PARAM_CHAPTER_AUTHOR}) {
            paramList.add(new ParameterDefinitionImpl(
                    param, DataTypeDefinition.TEXT, true, getParamDisplayLabel(param)));
        }
    }

    @Override
    protected void executeImpl(Action action, NodeRef actionedUponNodeRef) {
        if (serviceRegistry.getNodeService().exists(actionedUponNodeRef) == true) {
            // Get the new chapter folder's number, title, and author
            String chapterNumberString = (String)action.getParameterValue(PARAM_CHAPTER_NUMBER);
            if (!NumberUtils.isNumber(chapterNumberString)) {
                throw new AlfrescoRuntimeException("Provided chapter number is not a number: " + chapterNumberString);
            }
            int newChapterNumber = Integer.parseInt(chapterNumberString);
            if (newChapterNumber < 1) {
                // Set it to 1, so the new chapter is inserted as first
                newChapterNumber = 1;
            }
            String newChapterTitle = (String) action.getParameterValue(PARAM_CHAPTER_TITLE);
            String newChapterAuthor = (String) action.getParameterValue(PARAM_CHAPTER_AUTHOR);

            // Make sure we got an ISBN folder to which we are adding the chapter folder
            NodeRef isbnFolderNodeRef = actionedUponNodeRef;
            Serializable nodeName = serviceRegistry.getNodeService().getProperty(
                    isbnFolderNodeRef, ContentModel.PROP_NAME);
            if (nodeName == null) {
                throw new AlfrescoRuntimeException("ISBN Folder node name is null");
            }
            String isbn = (String) nodeName;
            if (!bestPubUtilsService.isISBN(isbn)) {
                throw new AlfrescoRuntimeException("ISBN Folder node name is not and ISBN number");
            }

            // Get the existing chapter folders for the ISBN sorted on chapter number (1, 2, 3, ...)
            Map<ChapterFolderProperties, NodeRef> chapterFolders =
                    bestPubUtilsService.getSortedChapterFolders(isbnFolderNodeRef);

            // Adjust existing chapter folders if we are inserting a new chapter (i.e. it is not added last)
            if (newChapterNumber > chapterFolders.size()) {
                // Make sure we are adding the new chapter right after the last existing chapter number
                newChapterNumber = chapterFolders.size() + 1;
            } else {
                // The new chapter should go in between existing chapters so we need to update chapter folder names
                // and numbers before adding the new chapter
                Map<ChapterFolderProperties, NodeRef> reverseChapters = ((TreeMap)chapterFolders).descendingMap();
                for (Map.Entry<ChapterFolderProperties, NodeRef> existingChapterFolder : reverseChapters.entrySet()) {
                    NodeRef existingChapterFolderNodeRef = existingChapterFolder.getValue();

                    Integer existingFolderChapterNumber = (Integer)existingChapterFolder.getKey().get(
                            BestPubMetadataFileModel.CHAPTER_METADATA_NUMBER_PROP_NAME);
                    int updatedChapterNumber = existingFolderChapterNumber + 1;
                    String updatedChapterFolderName = bestPubUtilsService.getChapterFolderName(updatedChapterNumber);
                    serviceRegistry.getNodeService().setProperty(
                            existingChapterFolderNodeRef, ContentModel.PROP_NAME, updatedChapterFolderName);
                    serviceRegistry.getNodeService().setProperty(existingChapterFolderNodeRef,
                            ChapterInfoAspect.Prop.CHAPTER_NUMBER, updatedChapterNumber);

                    // Update chapter number metadata for any files in chapter folder
                    // TODO:

                    // Check if we are at the position where we should add the new chapter folder, if so break out of loop
                    // as we don't need to update chapter folders that are coming before the new chapter in order
                    if (existingFolderChapterNumber == newChapterNumber) {
                        break;
                    }
                }
            }

            // Now create the new chapter folder with basic chapter metadata
            String chapterFolderName = bestPubUtilsService.getChapterFolderName(newChapterNumber);
            FileInfo chapterFileInfo = serviceRegistry.getFileFolderService().create(
                    isbnFolderNodeRef, chapterFolderName, ChapterFolderType.QNAME);
            Map<QName, Serializable> chapterMetadataAspectProps = new HashMap<>();
            chapterMetadataAspectProps.put(ChapterInfoAspect.Prop.CHAPTER_NUMBER, newChapterNumber);
            chapterMetadataAspectProps.put(ChapterInfoAspect.Prop.CHAPTER_TITLE, newChapterTitle);
            chapterMetadataAspectProps.put(ChapterInfoAspect.Prop.CHAPTER_AUTHOR_NAME, newChapterAuthor);
            chapterMetadataAspectProps.put(ChapterInfoAspect.Prop.CHAPTER_METADATA_STATUS,
                    ChapterMetadataStatus.COMPLETED.toString());
            serviceRegistry.getNodeService().addAspect(chapterFileInfo.getNodeRef(),
                    ChapterInfoAspect.QNAME, chapterMetadataAspectProps);
            LOG.debug("Added chapter folder {} [chapterTitle={}]",
                    alfrescoRepoUtilsService.getDisplayPathForNode(chapterFileInfo.getNodeRef()),
                    newChapterTitle);


            // Copy book info metadata to the new chapter folder
            Set<QName> aspects = new HashSet<>();
            aspects.add(BestPubContentModel.BookInfoAspect.QNAME);
            alfrescoRepoUtilsService.copyAspects(isbnFolderNodeRef, chapterFileInfo.getNodeRef(), aspects);

            // Update ISBN folder metadata and set the number of chapters to one more
            int currenNumberOfChapters = (Integer) serviceRegistry.getNodeService().getProperty(
                    isbnFolderNodeRef, BestPubContentModel.BookInfoAspect.Prop.BOOK_NUMBER_OF_CHAPTERS);
            serviceRegistry.getNodeService().setProperty(isbnFolderNodeRef,
                    BestPubContentModel.BookInfoAspect.Prop.BOOK_NUMBER_OF_CHAPTERS, currenNumberOfChapters + 1);

        } else {
            LOG.error("Cannot create chapter folder, ISBN node reference does not exist {}", actionedUponNodeRef);
        }
    }
}

