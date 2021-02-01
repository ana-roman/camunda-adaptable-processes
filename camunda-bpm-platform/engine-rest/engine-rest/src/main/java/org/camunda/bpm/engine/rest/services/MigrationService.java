package org.camunda.bpm.engine.rest.services;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.migration.MigrationPlan;
import org.camunda.bpm.engine.migration.MigrationPlanExecutionBuilder;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.rest.exception.InvalidRequestException;
import org.camunda.bpm.model.bpmn.BpmnModelException;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.Task;

import javax.ws.rs.core.Response;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 *  Utility class to be used in the functionality of Adaptable Processes.
 */
public class MigrationService {

	public static PrintWriter writer;
	private final ProcessEngine engine;
	private final String originProcessInstanceId;

	public MigrationService(ProcessEngine processEngine, String originProcessInstanceId) {
		this.engine = processEngine;
		this.originProcessInstanceId = originProcessInstanceId;
	}

	public boolean performMigration(ProcessDefinition originProcessDefinition, ProcessDefinition targetProcessDefinition) {
		Collection<Task> originProcessDefinitionTaskList = getTaskListForProcessDefinition(originProcessDefinition);
		Collection<Task> targetProcessDefinitionTaskList = getTaskListForProcessDefinition(targetProcessDefinition);

		Task changedTaskInOrigin = getReplacedTask(originProcessDefinitionTaskList, targetProcessDefinitionTaskList);
		List<String> activeActivities = engine.getRuntimeService().getActiveActivityIds(originProcessInstanceId);
		if (!activeActivities.contains(changedTaskInOrigin.getId())) {
			// The replaced task was not currently active. Migration can happen normally.
			createAndExecuteMigrationWithoutMapping(originProcessDefinition.getId(), targetProcessDefinition.getId());
			return true;
		}

		// If we are here, it means that the changed task was active (or in the array of active activities).
		// First, check if the new task is of the same type as the old one.
		Task changedTaskInTarget = getReplacedTask(targetProcessDefinitionTaskList, originProcessDefinitionTaskList);
		if (changedTaskInOrigin.getElementType().getTypeName().equals(changedTaskInTarget.getElementType().getTypeName())) {
			// the migration can be executed with the mapping.
			createAndExecuteMigrationWithMapping(originProcessDefinition.getId(), targetProcessDefinition.getId(), changedTaskInOrigin.getId(), changedTaskInTarget.getId());
			return true;
		}

		// The new activity is of a different type than the origin one.
		// 1. Find the activity/Flow node that precedes this
		SequenceFlow sequenceFlow = changedTaskInOrigin.getIncoming().iterator().next();
		FlowNode previousNode = sequenceFlow.getSource();
		// 2. Move the execution to that node.
		engine.getRuntimeService().activateProcessInstanceById(originProcessInstanceId);
		engine.getRuntimeService().createProcessInstanceModification(originProcessInstanceId)
			.cancelAllForActivity(changedTaskInOrigin.getId())
			.startBeforeActivity(previousNode.getId())
			.execute();

		// 3. Perform the migration
		createAndExecuteMigrationWithoutMapping(originProcessDefinition.getId(), targetProcessDefinition.getId());

		// 4. Stop the previous node execution and move the execution on the flow sequence
		engine.getRuntimeService().createProcessInstanceModification(originProcessInstanceId)
			.cancelAllForActivity(previousNode.getId())
			.execute();
		engine.getRuntimeService().createProcessInstanceModification(originProcessInstanceId)
			.startTransition(changedTaskInTarget.getIncoming().iterator().next().getId())
			.execute();

		return true;
	}


	private Task getReplacedTask(Collection<Task> origin, Collection<Task> target) {
		List<Task> changed = origin.stream().filter(
			originTask -> target.stream().noneMatch(targetTask -> haveEqualData(originTask, targetTask))
		).collect(Collectors.toList());
		if (changed.isEmpty()) {
			throw new InvalidRequestException(Response.Status.INTERNAL_SERVER_ERROR, "Cant find the replaced activity");
		}
		return changed.get(0);
	}


	private FlowNode getPreviousFlowNode(Task task) {
		return task.getIncoming().iterator().next().getSource();
	}

	private Activity getPreviousActivity(Task changedTask) {
		FlowNode parentTask = changedTask;
		while (true) {
			try {
				parentTask = parentTask.getIncoming().iterator().next().getSource();
				if (parentTask instanceof Activity) {
					return (Activity) parentTask;
				}
			} catch (BpmnModelException e) {
				throw new BpmnModelException("Unable to determine an unique previous activity of " + changedTask.getId(), e);
			}
		}
	}

	private Collection<Task> getTaskListForProcessDefinition(ProcessDefinition processDefinition) {
		BpmnModelInstance bpmnModelInstance = engine.getRepositoryService().getBpmnModelInstance(processDefinition.getId());
		Collection<Task> tasks = bpmnModelInstance.getModelElementsByType(Task.class);
		if (tasks.isEmpty()) {
			throw new InvalidRequestException(Response.Status.INTERNAL_SERVER_ERROR, "No tasks found for Process Definition: " + processDefinition.getId());
		}

		return tasks;
	}


	private void createAndExecuteMigrationWithoutMapping(String originProcessDefinitionId, String targetProcessDefinitionId) {
		// Create the migration plan
		MigrationPlan migrationPlan = engine.getRuntimeService()
			.createMigrationPlan(originProcessDefinitionId, targetProcessDefinitionId)
			.mapEqualActivities()
			.updateEventTriggers()
			.build();

		// Create the Migration Builder
		MigrationPlanExecutionBuilder builder = engine.getRuntimeService()
			.newMigration(migrationPlan)
			.processInstanceIds(originProcessInstanceId);
		// Execute the migration
		builder.execute();
	}


	private void createAndExecuteMigrationWithMapping(String originProcessDefinitionId, String targetProcessDefinitionId, String originActivityId, String targetActivityId) {
		// Create the migration plan
		MigrationPlan migrationPlan = engine.getRuntimeService()
			.createMigrationPlan(originProcessDefinitionId, targetProcessDefinitionId)
			.mapEqualActivities()
			.updateEventTriggers()
			.mapActivities(originActivityId, targetActivityId)
			.build();

		// Create the Migration Builder
		MigrationPlanExecutionBuilder builder = engine.getRuntimeService()
			.newMigration(migrationPlan)
			.processInstanceIds(originProcessInstanceId);
		// Execute the migration
		builder.execute();
	}

	private boolean haveEqualData(Task origin, Task target) {
		return (origin.getId().equals(target.getId())) &&
			(origin.getName().equals(target.getName())) &&
			(origin.getElementType().getTypeName().equals(
				target.getElementType().getTypeName()));
	}

}
