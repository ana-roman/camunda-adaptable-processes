package org.camunda.bpm.engine.rest.services;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.migration.MigrationPlan;
import org.camunda.bpm.engine.migration.MigrationPlanExecutionBuilder;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.rest.dto.runtime.ProcessInstanceDto;
import org.camunda.bpm.engine.rest.exception.InvalidRequestException;
import org.camunda.bpm.engine.runtime.ProcessInstance;
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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 *  Utility class to be used in the functionality of Adaptable Processes.
 */
public class MigrationService {

	public static PrintWriter writer;
	private final ProcessEngine engine;
	private final String originProcessInstanceId;
	private final ProcessDefinition originProcessDefinition;
	private final ProcessDefinition targetProcessDefinition;
	private final Collection<Task> originProcessDefinitionTaskList;
	private final Collection<Task> targetProcessDefinitionTaskList;

	public MigrationService(ProcessEngine processEngine, String originProcessInstanceId, ProcessDefinition originProcessDefinition, ProcessDefinition targetProcessDefinition) {
		this.engine = processEngine;
		this.originProcessInstanceId = originProcessInstanceId;
		this.originProcessDefinition = originProcessDefinition;
		this.targetProcessDefinition = targetProcessDefinition;

		this.originProcessDefinitionTaskList = getTaskListForProcessDefinition(originProcessDefinition);
		this.targetProcessDefinitionTaskList = getTaskListForProcessDefinition(targetProcessDefinition);
	}

	public ProcessInstanceDto performAdaptableMigration() {
		Task changedTaskInOrigin = getReplacedTask(originProcessDefinitionTaskList, targetProcessDefinitionTaskList);
		if (changedTaskInOrigin == null) {
			// no activity was changed, more were added. Migration can happen normally.
			createAndExecuteMigrationWithoutMapping(originProcessDefinition.getId(), targetProcessDefinition.getId());
			return null;
		}

		List<String> activeActivities = engine.getRuntimeService().getActiveActivityIds(originProcessInstanceId);
		if (!activeActivities.contains(changedTaskInOrigin.getId())) {
			// The replaced task was not currently active. Migration can happen normally.
			createAndExecuteMigrationWithoutMapping(originProcessDefinition.getId(), targetProcessDefinition.getId());
			return null;
		}

		// If we are here, it means that the changed task was active (or in the array of active activities).
		// First, check if the new task is of the same type as the old one.
		Task changedTaskInTarget = getReplacedTask(targetProcessDefinitionTaskList, originProcessDefinitionTaskList);
		if (changedTaskInTarget != null) {
			if (changedTaskInOrigin.getElementType().getTypeName().equals(changedTaskInTarget.getElementType().getTypeName())) {
				// the migration can be executed with the mapping.
				createAndExecuteMigrationWithMapping(originProcessDefinition.getId(), targetProcessDefinition.getId(), changedTaskInOrigin.getId(), changedTaskInTarget.getId());
				return null;
			}
		}
		return startProcessInstanceBeforeTask(changedTaskInOrigin);
	}

	public ProcessInstanceDto adaptableFromActivity(String activityId) {
		Optional<Task> taskOptional = targetProcessDefinitionTaskList.stream().filter(originTask -> originTask.getId().equals(activityId)).findAny();
		if (!taskOptional.isPresent()) {
			throw new InvalidRequestException(Response.Status.INTERNAL_SERVER_ERROR, "No Activity could be found with the given ID: " + activityId);
		}

		return startProcessInstanceBeforeTask(taskOptional.get());
	}

	private ProcessInstanceDto startProcessInstanceBeforeTask(Task task) {
		// Fetch the variables of the origin process instance
		Map<String, Object> variables = engine.getRuntimeService().getVariables(originProcessInstanceId);
		//  Find the Sequence Flow node that precedes the Task
		SequenceFlow previousSequenceFlow = task.getIncoming().iterator().next();

		// Start a new process instance of the new process definition
		ProcessInstance processInstance = engine.getRuntimeService()
			.createProcessInstanceById(targetProcessDefinition.getId())
			.setVariables(variables)
			.startTransition(previousSequenceFlow.getId()) // TODO: this doesn't consider flow conditions.
			.executeWithVariablesInReturn(false, false);

		engine.getRuntimeService().deleteProcessInstance(originProcessInstanceId, "Process Migrated");
		return ProcessInstanceDto.fromProcessInstance(processInstance);

	}

	private Task getReplacedTask(Collection<Task> origin, Collection<Task> target) {
		List<Task> changed = origin.stream().filter(
			originTask -> target.stream().noneMatch(targetTask -> haveEqualData(originTask, targetTask))
		).collect(Collectors.toList());
		if (changed.isEmpty()) {
			return null;
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

		createAndExecuteMigrationBuilder(migrationPlan);

	}

	private void createAndExecuteMigrationWithMapping(String originProcessDefinitionId, String targetProcessDefinitionId, String originActivityId, String targetActivityId) {
		// Create the migration plan
		MigrationPlan migrationPlan = engine.getRuntimeService()
			.createMigrationPlan(originProcessDefinitionId, targetProcessDefinitionId)
			.mapEqualActivities()
			.updateEventTriggers()
			.mapActivities(originActivityId, targetActivityId)
			.build();

		createAndExecuteMigrationBuilder(migrationPlan);
	}

	private void createAndExecuteMigrationBuilder(MigrationPlan migrationPlan) {
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
