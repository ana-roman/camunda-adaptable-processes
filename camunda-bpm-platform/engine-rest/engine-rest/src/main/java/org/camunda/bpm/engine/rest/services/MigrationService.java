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
			startProcessInstanceBeforeTask(changedTasksInOrigin.get(0));
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
				startProcessInstanceBeforeTask(task);
				return;
			}
		}

		// If we are here, it means that no task was deleted, but tasks were replaced.
		// First, check if the new tasks are of the same type as the old one.
		List<String> safeToMigrateActivities = new ArrayList<>();

		// TODO: to mention that, if the user wants to swap out a task for another task of the
		//  same type, then the IDs of the tasks should stay the same so that we can perform the migration
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

		if ((long) safeToMigrateActivities.size() != (long) activeActivities.size()) {
			// Not all activities can be safely migrated. Fallback to the adaptable logic
			writer.println("Some of the active tasks have changed type, and thus could not be migrated.");
			List<Task> tasks = changedTasksInTarget.stream().filter(startingTask -> activeActivities.contains(startingTask.getId())).collect(Collectors.toList());
			if (!tasks.isEmpty()) {
				Task task = tasks.get(0);
				writer.println("Selected task for stepping back: " + task.getId());
				startProcessInstanceBeforeTask(task);
				return;
			}
			throwError("Something went wrong with the adaptable flow - could not find an activity to start from. Reversing deployment.");
			return;
		}

		// All activities can be migrated.
		createAndExecuteMigrationWithoutMapping(originProcessDefinition.getId(), targetProcessDefinition.getId());
		writer.println("The active tasks could be safely migrated. Migration took place normally");
	}

	public void adaptableFromActivity(String activityId) {
		Optional<Task> taskOptional = originProcessDefinitionTaskList.stream().filter(originTask -> originTask.getId().equals(activityId)).findAny();
		if (!taskOptional.isPresent()) {
			throwError("No Activity could be found with the given ID: " + activityId);
			return;
		}

		startProcessInstanceBeforeTask(taskOptional.get());
	}


	private void startProcessInstanceBeforeTask(Task task) {
		// Fetch the variables of the origin process instance
		Map<String, Object> variables = engine.getRuntimeService().getVariables(originProcessInstanceId);
		//  Find the Sequence Flow node that precedes the Task
		SequenceFlow previousSequenceFlow = task.getIncoming().iterator().next();
		FlowNode previousTask = previousSequenceFlow.getSource();
		ProcessInstance processInstance;

		if (previousTask instanceof Gateway) {
			if (previousTask.getIncoming().size() > 1) {
				writer.println("Starting new process at (after) task: " + previousTask.getId());
				// Start a new process instance of the new process definition
				processInstance = engine.getRuntimeService()
					.createProcessInstanceById(targetProcessDefinition.getId())
					.setVariables(variables)
					.startAfterActivity(previousTask.getId()) // this will consider flow conditions too
					.executeWithVariablesInReturn(false, false);
			} else {
				writer.println("Starting new process at (before) task: " + previousTask.getId());
				// Start a new process instance of the new process definition
				processInstance = engine.getRuntimeService()
					.createProcessInstanceById(targetProcessDefinition.getId())
					.setVariables(variables)
					.startBeforeActivity(previousTask.getId()) // this will consider flow conditions too
					.executeWithVariablesInReturn(false, false);
			}

		} else {
			writer.println("Starting new process at transition: " + previousSequenceFlow.getId());
			processInstance = engine.getRuntimeService()
				.createProcessInstanceById(targetProcessDefinition.getId())
				.setVariables(variables)
				.startTransition(previousSequenceFlow.getId()) // TODO: mention that this doesn't consider flow conditions.
				.executeWithVariablesInReturn(false, false);
		}

		engine.getRuntimeService().deleteProcessInstance(originProcessInstanceId, "Process Migrated");
		writer.println("The adaptable process was deployed with Process Instance ID: " + processInstance.getId());

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
			throwError("No tasks found for Process Definition: " + processDefinition.getId());
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
