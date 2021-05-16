package org.camunda.bpm.engine.rest.services;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.migration.MigrationPlan;
import org.camunda.bpm.engine.migration.MigrationPlanExecutionBuilder;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.rest.exception.InvalidRequestException;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.model.bpmn.BpmnModelException;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.*;

import javax.ws.rs.core.Response;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

/**
 *  Utility class to be used in the functionality of Adaptable Processes.
 */
public class MigrationService {

	private final PrintWriter writer;

	private final ProcessEngine engine;
	private final String originProcessInstanceId;
	private final ProcessDefinition originProcessDefinition;
	private final ProcessDefinition targetProcessDefinition;
	private final Collection<Task> originProcessDefinitionTaskList;
 	private final Collection<Task> targetProcessDefinitionTaskList;

	public MigrationService(ProcessEngine processEngine, String originProcessInstanceId, ProcessDefinition originProcessDefinition, ProcessDefinition targetProcessDefinition, PrintWriter writer) {
		this.engine = processEngine;
		this.originProcessInstanceId = originProcessInstanceId;
		this.originProcessDefinition = originProcessDefinition;
		this.targetProcessDefinition = targetProcessDefinition;

		this.originProcessDefinitionTaskList = getTaskListForProcessDefinition(originProcessDefinition);
		this.targetProcessDefinitionTaskList = getTaskListForProcessDefinition(targetProcessDefinition);
		this.writer = writer;
	}

	public void performAdaptableMigration() {
		List<Task> changedTasksInOrigin = getReplacedTasks(originProcessDefinitionTaskList, targetProcessDefinitionTaskList);
		if (changedTasksInOrigin == null) {
			// no activity was changed, more were added. Migration can happen normally.
			createAndExecuteMigrationWithoutMapping(originProcessDefinition.getId(), targetProcessDefinition.getId());
			writer.println("No activities from the origin process were changed. Migration took place normally.");
			return;
		}

		List<String> activeActivities = engine.getRuntimeService().getActiveActivityIds(originProcessInstanceId);

		writer.println("\nActive activities in Origin: ");
		activeActivities.forEach(activeActivity -> writer.println("     " + activeActivity));
		writer.println("Changed tasks in origin: ");
		changedTasksInOrigin.forEach(task -> writer.println("     " + task.getId()));

		List<Task> activeChangedTasks = changedTasksInOrigin.stream().filter(task -> activeActivities.contains(task.getId())).collect(Collectors.toList());
		if (activeChangedTasks.isEmpty()) {
			// The replaced tasks were not currently active. Migration can happen normally.
			createAndExecuteMigrationWithoutMapping(originProcessDefinition.getId(), targetProcessDefinition.getId());
			writer.println("The tasks that changed from the origin process were not active. Migration took place normally");
			return;
		}

		// If we are here, it means that the changed tasks were active (or in the array of active activities).
		// First, check if active tasks were deleted.
		List<Task> changedTasksInTarget = getReplacedTasks(targetProcessDefinitionTaskList, originProcessDefinitionTaskList);
		if (changedTasksInTarget == null) {
			// If the list of changed tasks in target is empty, it means that no new tasks were added.
			// In this case, it means that tasks were deleted.
			// Start the new process before one of those tasks.
			String activityId = changedTasksInOrigin.get(0).getId(); // new task is arbitrarily chosen.
			startProcessInstanceBeforeNode(changedTasksInOrigin.get(0));
			writer.println("Active tasks were deleted. New process will start from activity with ID: " + activityId);
			return;
		}
		writer.println("Changed tasks in target:");
		changedTasksInTarget.forEach(task -> writer.println("     " + task.getId()));
		writer.println("");

		List<String> changedTasksInTargetIds = changedTasksInTarget.stream().map(BaseElement::getId).collect(Collectors.toList());
		List<String> changedTasksInOriginIds = changedTasksInOrigin.stream().map(BaseElement::getId).collect(Collectors.toList());

		// This statement is similar to the previous one, however the two branches check for different situations.
		for (String changedTaskInOriginId: changedTasksInOriginIds) {
			if (!changedTasksInTargetIds.contains(changedTaskInOriginId) && activeActivities.contains(changedTaskInOriginId)) {
				// an active task is not present in Target any more - it was deleted.
				// Deploy and start new instance from before that activity
				writer.println("An active activity was deleted: " + changedTaskInOriginId);
				// find the task that was deleted
				Task task = changedTasksInOrigin.stream().filter(startingTask -> startingTask.getId().equals(changedTaskInOriginId)).collect(Collectors.toList()).get(0);
				// start the new process before the deleted task
				startProcessInstanceBeforeNode(task);
				return;
			}
		}

		// If we are here, it means that no task was deleted, but tasks were replaced.
		// First, check if the new tasks are of the same type as the old one.
		List<String> safeToMigrateActivities = new ArrayList<>();

		changedTasksInTarget.forEach(changedTask -> {
			// for all of the changed/new tasks in target, check whether they were also present in origin.
			Optional<Task> optionalTask = changedTasksInOrigin.stream().filter(task -> task.getId().equals(changedTask.getId())).findAny();
			if (optionalTask.isPresent()) {
				// if they were also present in Origin, check whether they had the same type
				// (newly added activities are not checked for here)
				if (changedTask.getElementType().getTypeName().equals(optionalTask.get().getElementType().getTypeName())) {
					// This means that the activities have been changed, but their types stayed the same
					safeToMigrateActivities.add(changedTask.getId());
			  }
			 }
		});

		if ((long) safeToMigrateActivities.size() != (long) changedTasksInOrigin.size()) {
			// Not all activities can be safely migrated. Fallback to the adaptable logic
			writer.println("Some of the active tasks have changed type, and thus could not be migrated.");
			List<Task> tasks = changedTasksInTarget.stream().filter(startingTask -> activeActivities.contains(startingTask.getId())).collect(Collectors.toList());
			if (!tasks.isEmpty()) {
				Task task = tasks.get(0);
				writer.println("Selected task for stepping back: " + task.getId());
				startProcessInstanceBeforeNode(task);
				return;
			}
			throwError("Something went wrong with the adaptable flow - could not find an activity to start from. Reversing deployment.");
			return;
		}

		// All activities can be migrated.
		createAndExecuteMigrationWithoutMapping(originProcessDefinition.getId(), targetProcessDefinition.getId());
		writer.println("The active tasks could be safely migrated. Migration took place normally");
	}

	public void startProcessInstanceAtNode(String activityId) {
		Collection<FlowNode> originProcessDefinitionFlowNodeList = getFlowNodeListForProcessDefinition(originProcessDefinition);
		if (originProcessDefinitionFlowNodeList == null) {
			throwError("Could not get the list of Nodes for the new mode. Reversing deployment. " + activityId);
			return;
		}

		Optional<FlowNode> taskOptional = originProcessDefinitionFlowNodeList.stream().filter(originTask -> originTask.getId().equals(activityId)).findAny();
		if (!taskOptional.isPresent()) {
			throwError("No Node could be found with the given ID: " + activityId);
			return;
		}

		ProcessInstanceStarter starter = new ProcessInstanceStarter(engine, targetProcessDefinition.getId(), writer);
		Map<String, Object> variables = engine.getRuntimeService().getVariables(originProcessInstanceId);
		FlowNode node = taskOptional.get();
		if (node instanceof Gateway) {
			starter.startProcessInstanceAtGateway((Gateway) node, variables);
		} else {
			SequenceFlow flow = taskOptional.get().getIncoming().iterator().next();
			starter.startProcessInstanceAtTransition(flow, variables);
		}
	}

	private void startProcessInstanceBeforeNode(FlowNode node) {
		//  Find the Sequence Flow node that precedes the Task
		SequenceFlow previousSequenceFlow = node.getIncoming().iterator().next();
		FlowNode previousTask = previousSequenceFlow.getSource();

		// Fetch the variables of the origin process instance
		Map<String, Object> variables = engine.getRuntimeService().getVariables(originProcessInstanceId);
		ProcessInstanceStarter starter = new ProcessInstanceStarter(engine, targetProcessDefinition.getId(), writer);

		if (previousTask instanceof Gateway) {
			starter.startProcessInstanceAtGateway((Gateway) previousTask, variables);
		} else {
			starter.startProcessInstanceAtTransition(previousSequenceFlow, variables);
		}

		engine.getRuntimeService().deleteProcessInstance(originProcessInstanceId, "Process Migrated");
	}

	private List<Task> getReplacedTasks(Collection<Task> origin, Collection<Task> target) {
		List<Task> changed = origin.stream().filter(
			originTask -> target.stream().noneMatch(targetTask -> haveEqualData(originTask, targetTask))
		).collect(Collectors.toList());
		if (changed.isEmpty()) {
			return null;
		}
		return changed;
	}

	private Collection<Task> getTaskListForProcessDefinition(ProcessDefinition processDefinition) {
		BpmnModelInstance bpmnModelInstance = engine.getRepositoryService().getBpmnModelInstance(processDefinition.getId());
		Collection<Task> tasks = bpmnModelInstance.getModelElementsByType(Task.class);
		if (tasks.isEmpty()) {
			throwError("No tasks found for Process Definition: " + processDefinition.getId());
			return null;
		}

		return tasks;
	}

	private Collection<FlowNode> getFlowNodeListForProcessDefinition(ProcessDefinition processDefinition) {
		BpmnModelInstance bpmnModelInstance = engine.getRepositoryService().getBpmnModelInstance(processDefinition.getId());
		Collection<FlowNode> tasks = bpmnModelInstance.getModelElementsByType(FlowNode.class);
		if (tasks.isEmpty()) {
			throwError("No flow nodes found for Process Definition: " + processDefinition.getId());
			return null;
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

	private void createAndExecuteMigrationBuilder(MigrationPlan migrationPlan) {
		// Create the Migration Builder and pass it the MigrationPlan
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

	private void throwError(String errorMessage) {
		writer.println("An error occurred. Reversing deployment.");
		writer.close();
		engine.getRepositoryService().deleteDeployment(targetProcessDefinition.getDeploymentId(), true);
		throw new InvalidRequestException(Response.Status.BAD_REQUEST, errorMessage);
	}
}
