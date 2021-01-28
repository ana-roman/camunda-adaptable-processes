/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.rest.impl;

import org.camunda.bpm.engine.AuthorizationException;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.impl.util.IoUtil;
import org.camunda.bpm.engine.rest.exception.InvalidRequestException;
import org.camunda.bpm.engine.rest.extracts.ProcessActivityExtract;
import org.camunda.bpm.engine.rest.mapper.MultipartFormData;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.*;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.camunda.bpm.model.xml.type.ModelElementType;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class RestHelper {

    private PrintWriter getPrintWriter() {
        File file = new File("text/output_file4.txt");
        try {
            boolean newfile = file.createNewFile();
            return new PrintWriter(new FileWriter(file));
        } catch (Exception e) {
            throw new InvalidRequestException(Response.Status.SEE_OTHER, "Cant create file");
        }
    }
    private void printDetailsAboutPayload(UriInfo uriInfo, MultipartFormData payload) {
        // THIS WORKS NOW. DONT TOUCH IT
        PrintWriter writer = getPrintWriter();
        writer.println("hello friends");
        if (payload!= null) {
            writer.println("there is some paylaod");
            Map<String, MultipartFormData.FormPart> mapping = payload.getFormParts();
            if (mapping != null) {
                writer.println("Size: " + mapping.size());
                writer.println("Process instance to suspend: " + mapping.get("process-instance-id").getTextContent());
                writer.println("Printing the form parts:");
                mapping.forEach((String key, MultipartFormData.FormPart value) -> {
                    writer.println("Key: " +key);
                    writer.println("Text: ");
                    writer.println(value.getTextContent());
                    writer.println("\n\n");
                });
            }
        } else {
            writer.println("payload null");
        }

        writer.close();
    }




    public List<ProcessActivityExtract> getProcessActivitiesByProcessId(ProcessEngine engine, String processDefinitionId){

        List<ProcessActivityExtract> list = new ArrayList<>();
        Collection<ModelElementInstance> taskInstances;
        InputStream processModelInputStream = null;

        try {
            processModelInputStream = engine.getRepositoryService().getProcessModel(processDefinitionId);
            byte[] processModel = IoUtil.readInputStream(processModelInputStream, "processModelBpmn20Xml");
            InputStream stream = new ByteArrayInputStream(processModel);
            BpmnModelInstance modelInstance = Bpmn.readModelFromStream(stream);
            ModelElementType taskType =  modelInstance.getModel().getType(Task.class);
            taskInstances = modelInstance.getModelElementsByType(taskType);

            for(ModelElementInstance task: taskInstances){
                // TODO Investigate!
                // not used right now but i could maybe try to use this to generate a relational mapping
                // of the activities in a model so I can edit the tree later on maybe?
                task.getChildElementsByType(taskType);
                list.add(new ProcessActivityExtract(task.getAttributeValue("id"), task.getAttributeValue("name")));
            }

            return list;

        } catch (AuthorizationException e) {
            throw e;
        } catch (ProcessEngineException e) {
            throw new InvalidRequestException(Response.Status.BAD_REQUEST, e, "No matching definition with id " + processDefinitionId);
        } finally {
            IoUtil.closeSilently(processModelInputStream);
        }


    }
    public String createDummyModel(String processDefinitionId) {
        BpmnModelInstance modelInstance = Bpmn.createEmptyModel();
        Definitions definitions = modelInstance.newInstance(Definitions.class);
        definitions.setTargetNamespace("https://camunda.org/examples");
        modelInstance.setDefinitions(definitions);

        Process process = modelInstance.newInstance(Process.class);
        definitions.addChildElement(process);

        StartEvent startEvent = modelInstance.newInstance(StartEvent.class);
        startEvent.setId("start");
        process.addChildElement(startEvent);

        UserTask userTask = modelInstance.newInstance(UserTask.class);
        userTask.setId("task");
        userTask.setName("User Task");
        process.addChildElement(userTask);

        SequenceFlow sequenceFlow = modelInstance.newInstance(SequenceFlow.class);
        sequenceFlow.setId("flow1");
        process.addChildElement(sequenceFlow);
        connect(sequenceFlow, startEvent, userTask);

        EndEvent endEvent = modelInstance.newInstance(EndEvent.class);
        endEvent.setId("end");
        process.addChildElement(endEvent);

        sequenceFlow = modelInstance.newInstance(SequenceFlow.class);
        sequenceFlow.setId("flow2");
        process.addChildElement(sequenceFlow);
        connect(sequenceFlow, userTask, endEvent);

        File file = null;
        try {
            file = File.createTempFile("bpmn", ".bpmn");
        } catch (IOException e) {
            e.printStackTrace();
        }
        Bpmn.writeModelToFile(file, modelInstance);
        return "Success";
    }
    public void connect(SequenceFlow flow, FlowNode from, FlowNode to) {
        flow.setSource(from);
        from.getOutgoing().add(flow);
        flow.setTarget(to);
        to.getIncoming().add(flow);
    }
}
