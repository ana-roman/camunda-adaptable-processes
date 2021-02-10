package org.camunda.bpm.engine.rest.services;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.repository.DeploymentBuilder;
import org.camunda.bpm.engine.repository.DeploymentWithDefinitions;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.rest.dto.LinkableDto;
import org.camunda.bpm.engine.rest.dto.repository.DeploymentWithDefinitionsDto;
import org.camunda.bpm.engine.rest.dto.runtime.ProcessInstanceDto;
import org.camunda.bpm.engine.rest.exception.InvalidRequestException;
import org.camunda.bpm.engine.rest.mapper.MultipartFormData;
import org.camunda.bpm.engine.runtime.ProcessInstance;

import javax.ws.rs.core.Response;
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

	public ProcessInstanceDto deployAdaptable() {
		if (multipartFormData == null) {
			throw new InvalidRequestException(Response.Status.BAD_REQUEST, "No data could be found in the Request");
		}

		ProcessInstance originProcessInstance = extractOriginProcessInstance(multipartFormData);
		// 1. Suspend the process instance that we want to migrate.
		engine.getRuntimeService().suspendProcessInstanceById(originProcessInstance.getId());

		String originProcessDefinitionId = originProcessInstance.getProcessDefinitionId();
		ProcessDefinition originProcessDefinition = engine.getRepositoryService().getProcessDefinition(originProcessDefinitionId);

		// 2. Deploy the new process and fetch the new ProcessDefinition
		DeploymentWithDefinitions targetProcessDeploymentWithDefinitions = createAndDeployNewProcess(originProcessDefinition.getDeploymentId());
		if (targetProcessDeploymentWithDefinitions.getDeployedProcessDefinitions().isEmpty()) {
			throw new InvalidRequestException(Response.Status.INTERNAL_SERVER_ERROR, "Deployed process def is empty?");
		}

		ProcessDefinition targetProcessDefinition = targetProcessDeploymentWithDefinitions.getDeployedProcessDefinitions().get(0);
		if (targetProcessDefinition == null) {
			throw new InvalidRequestException(Response.Status.INTERNAL_SERVER_ERROR, "Could not fetch the target ProcessDefinition");
		}

		MigrationService migrationService = new MigrationService(engine, originProcessInstance.getId(), originProcessDefinition, targetProcessDefinition);
		// 3. Decide on the type of adaptation that needs to be performed.
		String activityId = extractActivityId(multipartFormData);
		if (activityId == null) {
			// no activity ID provided. Perform the migration.
			return migrationService.performAdaptableMigration();
			// Return DTO of the new deployment.
//			return DeploymentWithDefinitionsDto.fromDeployment(targetProcessDeploymentWithDefinitions);
		}

		return migrationService.adaptableFromActivity(activityId);
	}

	private DeploymentWithDefinitions createAndDeployNewProcess(String deploymentId) {
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


	private ProcessInstance extractOriginProcessInstance(MultipartFormData multipartFormData) {

		Map<String, MultipartFormData.FormPart> formParts = multipartFormData.getFormParts();
		if (formParts.get("process-instance-id") == null) {
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

	private String extractActivityId(MultipartFormData multipartFormData) {
		Map<String, MultipartFormData.FormPart> formParts = multipartFormData.getFormParts();
		if (formParts.get("activity-id") == null) {
			return null;
		}

		String activityId = formParts.get("activity-id").getTextContent();
		if (activityId == null || activityId.equals(" ")) {
			return null;
		}
		return activityId;
	}
}
