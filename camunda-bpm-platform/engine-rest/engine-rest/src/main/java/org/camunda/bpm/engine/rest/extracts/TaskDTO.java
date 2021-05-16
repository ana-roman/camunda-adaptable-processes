package org.camunda.bpm.engine.rest.extracts;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class TaskDTO {
    String name;
    String id;
    String typeName;
    String textContent;
    String parentName;

    public TaskDTO(String name, String id, String typeName, String textContent, String parentName) {
        this.name = name;
        this.id = id;
        this.typeName = typeName;
        this.textContent = textContent;
        this.parentName = parentName;
    }
}
