package org.camunda.bpm.engine.rest.extracts;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.Task;


@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ProcessTaskDto {
    public String taskId;
    public String taskName;
    public String taskTypeName;

    public ProcessTaskDto(Task task){
        this.taskId = task.getId();
        this.taskName = task.getName();
        this.taskTypeName = task.getElementType().getTypeName();
    }

    public ProcessTaskDto(Activity task){
        this.taskId = task.getId();
        this.taskName = task.getName();
        this.taskTypeName = task.getElementType().getTypeName();
    }

    public ProcessTaskDto(String id){
        this.taskId = id;
    }

    public ProcessTaskDto(org.camunda.bpm.engine.task.Task task){
        this.taskId = task.getId();
        this.taskName = task.getName();
        this.taskTypeName = task.getClass().toString();
    }

    public String getTaskTypeName() {
        return taskTypeName;
    }

    public String getTaskName() {
        return taskName;
    }

    public String getTaskId() {
        return taskId;
    }


}

