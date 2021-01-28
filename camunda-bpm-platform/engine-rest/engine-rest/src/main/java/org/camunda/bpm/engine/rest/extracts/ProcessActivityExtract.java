package org.camunda.bpm.engine.rest.extracts;

import com.fasterxml.jackson.annotation.JsonAutoDetect;


@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ProcessActivityExtract {

    String activityId;
    String activityName;

    public ProcessActivityExtract(String id, String name){
        this.activityId = id;
        this.activityName = name;
    }
}

