package org.camunda.bpm.engine.rest.services;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.migration.MigrationPlan;
import org.camunda.bpm.engine.migration.MigrationPlanExecutionBuilder;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.DeploymentBuilder;
import org.camunda.bpm.engine.repository.DeploymentWithDefinitions;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.rest.dto.repository.DeploymentDto;
import org.camunda.bpm.engine.rest.exception.InvalidRequestException;
import org.camunda.bpm.engine.rest.mapper.MultipartFormData;
import org.camunda.bpm.engine.runtime.ProcessInstance;

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

	public DeploymentDto deployAdaptableProcess() {
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
		// Create the migration plan
		MigrationPlan migrationPlan = engine.getRuntimeService()
			.createMigrationPlan(originProcessDefinitionId, targetProcessDefinitionId)
			.mapEqualActivities()
			.updateEventTriggers()
			.build();

		// Create the Migration Builder
		MigrationPlanExecutionBuilder builder = engine.getRuntimeService()
			.newMigration(migrationPlan)
			.processInstanceIds(originProcessInstance.getId());
		// Execute the migration
		builder.execute();

		// Activate new Process Definition.
		engine.getRepositoryService().activateProcessDefinitionById(targetProcessDefinitionId, true, null);
		return null;
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
