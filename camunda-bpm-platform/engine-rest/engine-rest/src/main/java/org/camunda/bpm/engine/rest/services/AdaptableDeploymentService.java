package org.camunda.bpm.engine.rest.services;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.migration.MigrationPlan;
import org.camunda.bpm.engine.migration.MigrationPlanExecutionBuilder;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.DeploymentBuilder;
import org.camunda.bpm.engine.repository.DeploymentWithDefinitions;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.rest.dto.repository.DeploymentDto;
import org.camunda.bpm.engine.rest.dto.repository.DeploymentWithDefinitionsDto;
import org.camunda.bpm.engine.rest.exception.InvalidRequestException;
import org.camunda.bpm.engine.rest.mapper.MultipartFormData;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;

import javax.ws.rs.core.Response;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Map;

/**
 *  Utility class to be used in the functionality of Adaptable Processes.
 */
public class AdaptableDeploymentService {

	public static PrintWriter writer;
	private final ProcessEngine engine;
	private final MultipartFormData multipartFormData;

	public AdaptableDeploymentService(ProcessEngine processEngine, MultipartFormData payload) {
		engine = processEngine;
		multipartFormData = payload;
	}

	/**
	 * TODO TOMORROW:
	 * Look at service tasks and maybe at script tasks too,
	 * to see if those are also ported correctly.
	 * Then I still have to look into migrating ... when we replace a task thats currently running
	 *
	 * TO MENTION:
	 *
	 * Before migrating, get the tasklists of both processes definitions. Compare them
	 * - if the second has more tasks, we are already covering that case
	 * - if the number of tasks is the same this means that one task has been changed. By comparing the task IDs
	 *      we can find which task is changed, namely the one that has disappeared. So I get the ID of that task
	 *      and I do the task mapping in the migration. I will map the task that was deleted to its parent task,
	 *      so that the token will be placed on the task before.
	 *
	 *      I could add a check whether the parent task is still present too. Because it could be that the user
	 *      deleted more than one task, so the task and the parent or maybe a whole branch. Then I could move the token
	 *      all the way up - but maybe leave this for later.
	 *
	 * TODO
	 * 1. Get the lists of task of both old and new PD
	 * 2. Compare the number of tasks in each list and implement what I wrote above.
	 *
	 *
	 */

// THIS WORKS DONT TOUCH IT.
	public DeploymentWithDefinitionsDto deployAdaptableProcess() {
		String targetProcessDefinitionId;

		// 1. Suspend the process instance that we want to migrate.
		ProcessInstance originProcessInstance = extractOriginProcessInstanceId(multipartFormData);
		String originProcessDefinitionId = originProcessInstance.getProcessDefinitionId();
		engine.getRuntimeService().suspendProcessInstanceById(originProcessInstance.getId());

		// 2. Deploy the new process and fetch the new ProcessDefinition
		DeploymentWithDefinitions targetProcessDeploymentWithDefinitions = createAndDeployNewProcess();
		ProcessDefinition processDefinition = targetProcessDeploymentWithDefinitions.getDeployedProcessDefinitions().get(0);

		if (processDefinition != null) {
			targetProcessDefinitionId = processDefinition.getId();
		} else {
			throw new InvalidRequestException(Response.Status.INTERNAL_SERVER_ERROR, "Could not fetch the target ProcessDefinition");
		}

		// 3. Migrate
		createAndExecuteMigration(originProcessDefinitionId, targetProcessDefinitionId, originProcessInstance.getId());

		// 4. Activate new Process Definition.
//		engine.getRepositoryService().activateProcessDefinitionById(targetProcessDefinitionId, true, null);

		// 5. Return DTO of the new deployment.
		return DeploymentWithDefinitionsDto.fromDeployment(targetProcessDeploymentWithDefinitions);
	}

	public DeploymentWithDefinitionsDto deployAdaptable() {
		String targetProcessDefinitionId;

		// 1. Suspend the process instance that we want to migrate.
		ProcessInstance originProcessInstance = extractOriginProcessInstanceId(multipartFormData);
//		ProcessInstance newProcess = engine.getRepositoryService().
		String originProcessDefinitionId = originProcessInstance.getProcessDefinitionId();
		engine.getRuntimeService().suspendProcessInstanceById(originProcessInstance.getId());

		// 2. Deploy the new process and fetch the new ProcessDefinition
		DeploymentWithDefinitions targetProcessDeploymentWithDefinitions = createAndDeployNewProcess();
		ProcessDefinition targetProcessDefinition = targetProcessDeploymentWithDefinitions.getDeployedProcessDefinitions().get(0);

		if (targetProcessDefinition != null) {
			targetProcessDefinitionId = targetProcessDefinition.getId();
		} else {
			throw new InvalidRequestException(Response.Status.INTERNAL_SERVER_ERROR, "Could not fetch the target ProcessDefinition");
		}

		MigrationService migrationService = new MigrationService(engine, originProcessInstance.getId());
		boolean result = migrationService.performMigration(engine.getRepositoryService().getProcessDefinition(originProcessDefinitionId), targetProcessDefinition);


		// 3. Migrate
//		createAndExecuteMigration(originProcessDefinitionId, targetProcessDefinitionId, originProcessInstance.getId());

		// 4. Activate new Process Definition.
		// EDIT: Do not activate, leave it suspended.
//		engine.getRepositoryService().activateProcessDefinitionById(targetProcessDefinitionId, true, null);

		// 5. Return DTO of the new deployment.
		return DeploymentWithDefinitionsDto.fromDeployment(targetProcessDeploymentWithDefinitions);
	}

	private void createAndExecuteMigration(String originProcessDefinitionId, String targetProcessDefinitionId, String originProcessInstanceId) {
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

	private DeploymentWithDefinitions createAndDeployNewProcess() {
		DeploymentBuilderService deploymentBuilderService = new DeploymentBuilderService(engine, multipartFormData);
		DeploymentBuilder deploymentBuilder = deploymentBuilderService.createDeploymentBuilder();
		if (!deploymentBuilder.getResourceNames().isEmpty()) {
			DeploymentWithDefinitions deploymentWithDefinitions = deploymentBuilder.deployWithResult();
			if (deploymentWithDefinitions != null) {
				return deploymentWithDefinitions;
			}
		}

		throw new InvalidRequestException(Response.Status.BAD_REQUEST, "The new process could not be deployed.");
	}


	private ProcessInstance extractOriginProcessInstanceId(MultipartFormData multipartFormData) {
		if (multipartFormData == null) {
			throw new InvalidRequestException(Response.Status.BAD_REQUEST, "No Mapping could be found in the Request");
		}

		Map<String, MultipartFormData.FormPart> formParts = multipartFormData.getFormParts();
		if (formParts.get("process-instance-id") == null) {
			writer.println("There was no Process Instance ID given");
			throw new InvalidRequestException(Response.Status.BAD_REQUEST, "No Process Instance ID was given.");
		}

		String originProcessInstanceId = formParts.get("process-instance-id").getTextContent();
		if (originProcessInstanceId == null || originProcessInstanceId.equals(" ")) {
			throw new InvalidRequestException(Response.Status.BAD_REQUEST, "No Process Instance ID was given.");
		}

		ProcessInstance originProcessInstance = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(originProcessInstanceId).singleResult();
		if (originProcessInstance == null) {
			throw new InvalidRequestException(Response.Status.BAD_REQUEST, "No Process Instance could be found with the given ID.");
		}
		return originProcessInstance;
	}
}
