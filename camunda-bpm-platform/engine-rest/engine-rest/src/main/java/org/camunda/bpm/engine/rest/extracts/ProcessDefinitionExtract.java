package org.camunda.bpm.engine.rest.extracts;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.util.List;

// The annotation overwrites the visibility of any private properties of the class,
// when serializing and deserializing.
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ProcessDefinitionExtract {

    private String processDefinitionId;
    private String processDefinitionName;
    private List<ProcessActivityExtract> processDefinitionActivityList;

    public ProcessDefinitionExtract(String id, String name, List<ProcessActivityExtract> list){

        this.processDefinitionId = id;
        this.processDefinitionName = name;
        this.processDefinitionActivityList = list;
    }

    public ProcessDefinitionExtract(String id, String name){

        this.processDefinitionId = id;
        this.processDefinitionName = name;
        this.processDefinitionActivityList = null;
    }
}
